package com.dt.manager.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the contents of an APK / XAPK / APKM (all ZIP containers) without
 * fully extracting them to disk. Each entry is parsed lazily.
 *
 * XAPK and APKM bundles contain multiple APK files plus assets / OBB data.
 * This class abstracts those away so the UI can treat them as folders.
 */
public class ApkInspector implements Closeable {

    private final File source;
    private final ZipFile zip;
    private final List<EntryInfo> entries;

    public ApkInspector(File file) throws IOException {
        this.source = file;
        this.zip = new ZipFile(file);
        this.entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory()) {
                entries.add(EntryInfo.from(e));
            }
        }
        Collections.sort(entries, Comparator.comparing(EntryInfo::getPath));
    }

    public File getSource() { return source; }

    public String getName() { return source.getName(); }

    public List<EntryInfo> listEntries() { return entries; }

    public List<EntryInfo> listInDirectory(String dir) {
        String prefix = dir;
        if (prefix.equals("/") || prefix.isEmpty()) prefix = "";
        else if (!prefix.endsWith("/")) prefix = prefix + "/";

        List<EntryInfo> result = new ArrayList<>();
        for (EntryInfo e : entries) {
            String p = e.getPath();
            if (prefix.isEmpty()) {
                if (!p.contains("/")) result.add(e);
            } else {
                if (p.startsWith(prefix)) {
                    String remainder = p.substring(prefix.length());
                    if (remainder.isEmpty()) continue;
                    if (!remainder.contains("/")) {
                        result.add(e);
                    } else {
                        // First directory segment becomes a virtual folder entry
                        String dirName = remainder.substring(0, remainder.indexOf('/'));
                        EntryInfo v = EntryInfo.virtualFolder(prefix + dirName);
                        if (!result.contains(v)) result.add(v);
                    }
                }
            }
        }
        // Deduplicate virtual folders
        Collections.sort(result, Comparator
                .comparingInt((EntryInfo e) -> e.isDirectory() ? 0 : 1)
                .thenComparing(EntryInfo::getName));
        return result;
    }

    public InputStream openStream(String path) throws IOException {
        ZipEntry e = zip.getEntry(path);
        if (e == null) throw new IOException("Entry not found: " + path);
        return zip.getInputStream(e);
    }

    public byte[] readAll(String path) throws IOException {
        try (InputStream in = openStream(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public String readFileText(String path) throws IOException {
        return new String(readAll(path), StandardCharsets.UTF_8);
    }

    public EntryInfo findEntry(String path) {
        for (EntryInfo e : entries) {
            if (e.getPath().equals(path)) return e;
        }
        return null;
    }

    /** Returns true if this ZIP contains a single APK and is itself an APK. */
    public boolean isPlainApk() {
        return source.getName().toLowerCase().endsWith(".apk");
    }

    public boolean isXapk() { return source.getName().toLowerCase().endsWith(".xapk"); }
    public boolean isApkm() { return source.getName().toLowerCase().endsWith(".apkm"); }

    /** Returns paths of APK files inside the bundle (used for install). */
    public List<String> findInnerApks() {
        List<String> out = new ArrayList<>();
        for (EntryInfo e : entries) {
            if (e.getPath().toLowerCase().endsWith(".apk")) out.add(e.getPath());
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    public static class EntryInfo {
        private final String path;
        private final long size;
        private final long time;
        private final boolean directory;

        private EntryInfo(String path, long size, long time, boolean directory) {
            this.path = path;
            this.size = size;
            this.time = time;
            this.directory = directory;
        }

        static EntryInfo from(ZipEntry e) {
            return new EntryInfo(e.getName(), e.getSize(), e.getTime(), e.isDirectory());
        }

        static EntryInfo virtualFolder(String path) {
            return new EntryInfo(path, 0, 0, true);
        }

        public String getPath() { return path; }
        public long getSize() { return size; }
        public long getTime() { return time; }
        public boolean isDirectory() { return directory; }

        public String getName() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }

        public String getParentPath() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EntryInfo)) return false;
            EntryInfo e = (EntryInfo) o;
            return directory == e.directory && path.equals(e.path);
        }

        @Override
        public int hashCode() {
            return path.hashCode() * 31 + Boolean.hashCode(directory);
        }
    }
}
