package com.dt.manager.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.format.Formatter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class FileUtils {

    private FileUtils() {}

    public static final String DATE_FORMAT = "dd-MM-yy HH:mm";

    public static String formatDate(long millis) {
        if (millis <= 0) return "—";
        return new SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(new Date(millis));
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "—";
        return Formatter.formatShortFileSize(null, bytes);
    }

    public static String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String prefix = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.2f %sB", bytes / Math.pow(1024, exp), prefix);
    }

    public static List<File> listFiles(File dir) {
        List<File> result = new ArrayList<>();
        if (dir == null || !dir.exists() || !dir.isDirectory()) return result;
        File[] children = dir.listFiles();
        if (children == null) return result;

        List<File> folders = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File f : children) {
            if (f.isHidden()) continue;
            if (f.isDirectory()) folders.add(f);
            else files.add(f);
        }

        Collections.sort(folders, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        Collections.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));

        result.addAll(folders);
        result.addAll(files);
        return result;
    }

    public static int countFolders(File dir) {
        List<File> all = listFiles(dir);
        int count = 0;
        for (File f : all) {
            if (f.isDirectory()) count++;
        }
        return count;
    }

    public static int countFiles(File dir) {
        List<File> all = listFiles(dir);
        int count = 0;
        for (File f : all) {
            if (f.isFile()) count++;
        }
        return count;
    }

    public static String diskSummary(File path) {
        if (path == null) return "";
        try {
            StatFs stat = new StatFs(path.getAbsolutePath());
            long total = stat.getTotalBytes();
            long free = stat.getAvailableBytes();
            long used = total - free;
            return humanReadable(used) + "/" + humanReadable(total);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static File copyToCache(Context ctx, InputStream in, String name) throws IOException {
        File out = new File(ctx.getCacheDir(), name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    /**
     * Copy a file or whole directory tree from src to dest. If dest exists,
     * a numeric suffix is appended to the name (e.g. "foo (1).apk").
     */
    public static File copy(File src, File destDir) throws IOException {
        File target = uniqueDestination(destDir, src.getName());
        if (src.isDirectory()) {
            copyDir(src, target);
        } else {
            copyFile(src, target);
        }
        return target;
    }

    private static void copyDir(File src, File dest) throws IOException {
        if (!dest.exists()) dest.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;
        for (File c : children) {
            File childDest = new File(dest, c.getName());
            if (c.isDirectory()) copyDir(c, childDest);
            else copyFile(c, childDest);
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Recursively delete a file or directory. */
    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        return f.delete();
    }

    /** If target name already exists in destDir, append " (N)" before extension. */
    public static File uniqueDestination(File destDir, String name) {
        File target = new File(destDir, name);
        if (!target.exists()) return target;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (true) {
            String candidate = base + " (" + i + ")" + ext;
            File t = new File(destDir, candidate);
            if (!t.exists()) return t;
            i++;
        }
    }

    public static File getRootStoragePath(Context ctx) {
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) return ext;
        File fallback = Environment.getDataDirectory();
        return fallback != null ? fallback : new File("/storage/emulated/0");
    }

    public static String mimeForName(String name) {
        if (name == null) return "*/*";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".xapk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".apkm")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".dex")) return "application/octet-stream";
        if (lower.endsWith(".xml")) return "text/xml";
        if (lower.endsWith(".arsc")) return "application/octet-stream";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".so")) return "application/octet-stream";
        return "*/*";
    }

    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
