#!/usr/bin/env python3
"""Minimal APK Signature Scheme v2 signer/verifier for this Android 8+ project."""

import datetime
import hashlib
import struct
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID

MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
RSA_PKCS1_SHA256 = 0x0103
CHUNK = 1024 * 1024


def lp(value: bytes) -> bytes:
    return struct.pack("<I", len(value)) + value


def find_eocd(data: bytes) -> int:
    start = max(0, len(data) - 22 - 65535)
    pos = data.rfind(b"PK\x05\x06", start)
    if pos < 0 or pos + 22 + struct.unpack_from("<H", data, pos + 20)[0] != len(data):
        raise ValueError("EOCD not found")
    return pos


def content_digest(sections) -> bytes:
    chunks = []
    for section in sections:
        for pos in range(0, len(section), CHUNK):
            part = section[pos:pos + CHUNK]
            chunks.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(part)) + part).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunks)) + b"".join(chunks)).digest()


def load_or_create_identity(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    key_path = directory / "packx-release-key.pem"
    cert_path = directory / "packx-release-cert.pem"
    if key_path.exists() and cert_path.exists():
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        return key, cert
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.datetime.now(datetime.timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.ORGANIZATION_NAME, "PackX Pro"),
                      x509.NameAttribute(NameOID.COMMON_NAME, "PackX Pro Release")])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                         serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return key, cert


def sign(source: Path, output: Path, identity_dir: Path) -> None:
    data = source.read_bytes()
    eocd_pos = find_eocd(data)
    cd_size, cd_offset = struct.unpack_from("<II", data, eocd_pos + 12)
    if cd_offset + cd_size != eocd_pos:
        raise ValueError("ZIP64 or non-contiguous central directory is unsupported")
    before, central, eocd = data[:cd_offset], data[cd_offset:eocd_pos], bytearray(data[eocd_pos:])
    digest = content_digest((before, central, bytes(eocd)))

    key, cert = load_or_create_identity(identity_dir)
    cert_der = cert.public_bytes(serialization.Encoding.DER)
    public_der = key.public_key().public_bytes(serialization.Encoding.DER,
                                                serialization.PublicFormat.SubjectPublicKeyInfo)
    digest_record = struct.pack("<I", RSA_PKCS1_SHA256) + lp(digest)
    signed_data = lp(lp(digest_record)) + lp(lp(cert_der)) + lp(b"")
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    signature_record = struct.pack("<I", RSA_PKCS1_SHA256) + lp(signature)
    signer = lp(signed_data) + lp(lp(signature_record)) + lp(public_der)
    v2_value = lp(lp(signer))
    pair = struct.pack("<Q", 4 + len(v2_value)) + struct.pack("<I", V2_ID) + v2_value
    block_size = len(pair) + 24
    block = struct.pack("<Q", block_size) + pair + struct.pack("<Q", block_size) + MAGIC

    struct.pack_into("<I", eocd, 16, cd_offset + len(block))
    output.write_bytes(before + block + central + eocd)
    verify(output)


def take_lp(data: bytes, pos: int = 0):
    size = struct.unpack_from("<I", data, pos)[0]
    return data[pos + 4:pos + 4 + size], pos + 4 + size


def verify(path: Path) -> None:
    data = path.read_bytes()
    eocd_pos = find_eocd(data)
    cd_offset = struct.unpack_from("<I", data, eocd_pos + 16)[0]
    size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    if data[cd_offset - 16:cd_offset] != MAGIC:
        raise ValueError("APK Signing Block missing")
    block_start = cd_offset - size - 8
    pos, end, v2 = block_start + 8, cd_offset - 24, None
    while pos < end:
        pair_size = struct.unpack_from("<Q", data, pos)[0]
        pair_id = struct.unpack_from("<I", data, pos + 8)[0]
        if pair_id == V2_ID:
            v2 = data[pos + 12:pos + 8 + pair_size]
        pos += 8 + pair_size
    if v2 is None:
        raise ValueError("v2 signature missing")

    signers, _ = take_lp(v2)
    signer, _ = take_lp(signers)
    signed_data, p = take_lp(signer)
    signatures, p = take_lp(signer, p)
    public_der, p = take_lp(signer, p)
    digests, q = take_lp(signed_data)
    certificates, q = take_lp(signed_data, q)
    _, q = take_lp(signed_data, q)
    digest_record, _ = take_lp(digests)
    stored_digest, _ = take_lp(digest_record, 4)
    signature_record, _ = take_lp(signatures)
    signature, _ = take_lp(signature_record, 4)
    cert_der, _ = take_lp(certificates)
    cert = x509.load_der_x509_certificate(cert_der)
    cert.public_key().verify(signature, signed_data, padding.PKCS1v15(), hashes.SHA256())
    if cert.public_key().public_bytes(serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo) != public_der:
        raise ValueError("public key mismatch")
    fixed_eocd = bytearray(data[eocd_pos:])
    struct.pack_into("<I", fixed_eocd, 16, block_start)
    actual = content_digest((data[:block_start], data[cd_offset:eocd_pos], bytes(fixed_eocd)))
    if actual != stored_digest:
        raise ValueError("APK content digest mismatch")
    print(f"verified APK Signature Scheme v2: {path}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: apk_v2_sign.py INPUT_UNSIGNED OUTPUT_SIGNED IDENTITY_DIR")
    sign(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
