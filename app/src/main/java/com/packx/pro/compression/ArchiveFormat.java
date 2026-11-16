package com.packx.pro.compression;

/** Archive types PackX can create. Reading supports many more (RAR, ISO, CAB…). */
public enum ArchiveFormat {
    SEVEN_Z("7Z", ".7z", "7z", null, true, true, true, "أصغر حجم • تشفير كامل"),
    ZIP("ZIP", ".zip", "zip", null, true, false, true, "يفتح على كل الأجهزة"),
    TAR_XZ("TAR.XZ", ".tar.xz", "tar", "xz", false, false, false, "ضغط قوي لأنظمة لينكس"),
    TAR_GZ("TAR.GZ", ".tar.gz", "tar", "gzip", false, false, false, "سريع وشائع"),
    TAR_BZ2("TAR.BZ2", ".tar.bz2", "tar", "bzip2", false, false, false, "جيد للنصوص"),
    TAR("TAR", ".tar", "tar", null, false, false, true, "تجميع بدون ضغط");

    public final String label;
    public final String extension;
    /** 7-Zip container type. */
    public final String type;
    /** Stream compressor wrapped around the container, or null. */
    public final String wrapper;
    public final boolean supportsPassword;
    public final boolean supportsHiddenNames;
    public final boolean supportsVolumes;
    public final String hint;

    ArchiveFormat(String label, String extension, String type, String wrapper, boolean supportsPassword,
                  boolean supportsHiddenNames, boolean supportsVolumes, String hint) {
        this.label = label;
        this.extension = extension;
        this.type = type;
        this.wrapper = wrapper;
        this.supportsPassword = supportsPassword;
        this.supportsHiddenNames = supportsHiddenNames;
        this.supportsVolumes = supportsVolumes;
        this.hint = hint;
    }

    @Override public String toString() { return label; }
}
