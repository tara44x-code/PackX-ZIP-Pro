package com.packx.pro.compression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns the flat entry list of an archive into folders that can be browsed. */
public final class ArchiveTree {
    public static final class Node {
        public final String name;
        /** Path exactly as the engine knows it; used to select the entry for extraction. */
        public final String path;
        public final Node parent;
        public boolean directory;
        public boolean encrypted;
        public long size;
        public long modified;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private long totalSize = -1;
        private int fileCount = -1;

        Node(String name, String path, Node parent) { this.name = name; this.path = path; this.parent = parent; }

        public List<Node> children() {
            List<Node> list = new ArrayList<>(children.values());
            Collections.sort(list, new Comparator<Node>() {
                @Override public int compare(Node a, Node b) {
                    if (a.directory != b.directory) return a.directory ? -1 : 1;
                    return a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
                }
            });
            return list;
        }

        public int childCount() { return children.size(); }

        /** Size of the file, or of everything below a folder. */
        public long totalSize() {
            if (totalSize < 0) {
                long sum = directory ? 0 : size;
                for (Node child : children.values()) sum += child.totalSize();
                totalSize = sum;
            }
            return totalSize;
        }

        public int fileCount() {
            if (fileCount < 0) {
                int sum = directory ? 0 : 1;
                for (Node child : children.values()) sum += child.fileCount();
                fileCount = sum;
            }
            return fileCount;
        }

        /** Path shown to the user, e.g. "docs/reports". Empty for the root. */
        public String displayPath() {
            if (parent == null) return "";
            String up = parent.displayPath();
            return up.isEmpty() ? name : up + "/" + name;
        }
    }

    private ArchiveTree() { }

    public static Node build(SevenZip.Listing listing) {
        Node root = new Node("", "", null);
        root.directory = true;
        for (SevenZip.Entry entry : listing.entries) {
            String normalized = entry.path.replace('\\', '/');
            Node current = root;
            StringBuilder walked = new StringBuilder();
            String[] parts = normalized.split("/");
            int last = -1;
            for (int i = 0; i < parts.length; i++) if (!parts[i].isEmpty() && !parts[i].equals(".")) last = i;
            if (last < 0) continue;
            for (int i = 0; i <= last; i++) {
                String part = parts[i];
                if (part.isEmpty() || part.equals(".")) continue;
                if (walked.length() > 0) walked.append('/');
                walked.append(part);
                Node next = current.children.get(part);
                if (next == null) {
                    next = new Node(part, i == last ? entry.path : walked.toString(), current);
                    next.directory = true;
                    current.children.put(part, next);
                }
                current = next;
            }
            current.directory = entry.directory || current.childCount() > 0;
            current.size = entry.size;
            current.modified = entry.modified;
            current.encrypted = entry.encrypted;
        }
        return root;
    }
}
