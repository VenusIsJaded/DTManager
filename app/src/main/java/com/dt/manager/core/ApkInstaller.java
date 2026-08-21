package com.dt.manager.core;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.dt.manager.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Installs APK / XAPK / APKM bundles.
 *
 * - APK: launches the system installer intent via FileProvider.
 * - XAPK/APKM: extracts the inner APK (and OBB if present) and stages the
 *   APK in cache, then triggers the system installer.
 *
 * The actual install confirmation dialog is handled by the system. This
 * class is just the entry point.
 */
public class ApkInstaller {

    public interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    private final Context ctx;
    private final Callback cb;

    public ApkInstaller(Context ctx, Callback cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    public void install(File file) {
        if (file == null || !file.exists()) {
            cb.onError("File does not exist");
            return;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk")) {
            installPlainApk(file);
        } else if (name.endsWith(".xapk")) {
            installBundle(file, "xapk");
        } else if (name.endsWith(".apkm")) {
            installBundle(file, "apkm");
        } else {
            cb.onError("Unsupported file type: " + name);
        }
    }

    private void installPlainApk(File apk) {
        try {
            Intent intent = buildInstallIntent(apk, "application/vnd.android.package-archive");
            ctx.startActivity(intent);
            cb.onSuccess("Install requested for " + apk.getName());
        } catch (Exception e) {
            cb.onError("Failed to launch installer: " + e.getMessage());
        }
    }

    private void installBundle(File bundle, String kind) {
        // Extract inner APK(s) to cache, install first one
        File stage = new File(ctx.getCacheDir(), "install_" + UUID.randomUUID().toString());
        stage.mkdirs();
        try (ApkInspector inspector = new ApkInspector(bundle)) {
            List<String> apks = inspector.findInnerApks();
            if (apks.isEmpty()) {
                cb.onError("No APK entries found inside " + bundle.getName());
                return;
            }
            // Install first APK via system intent, leave the rest for future splits support
            String firstApkPath = apks.get(0);
            File staged = new File(stage, new File(firstApkPath).getName());
            try (InputStream in = inspector.openStream(firstApkPath);
                 FileOutputStream out = new FileOutputStream(staged)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            // Try to also extract OBB if present (XAPK only)
            extractObbIfExists(inspector);
            Intent intent = buildInstallIntent(staged, "application/vnd.android.package-archive");
            ctx.startActivity(intent);
            cb.onSuccess("Install requested for " + staged.getName() + " (" + apks.size() + " APK(s) in bundle)");
        } catch (IOException e) {
            cb.onError("Failed to extract bundle: " + e.getMessage());
        }
    }

    private void extractObbIfExists(ApkInspector inspector) {
        for (ApkInspector.EntryInfo e : inspector.listEntries()) {
            String p = e.getPath().toLowerCase(Locale.ROOT);
            if (p.startsWith("android/") && p.endsWith(".obb")) {
                // Best-effort copy — ignore failures
                try {
                    File obbDest = new File(android.os.Environment.getExternalStorageDirectory(),
                            e.getPath());
                    obbDest.getParentFile().mkdirs();
                    try (InputStream in = inspector.openStream(e.getPath());
                         FileOutputStream out = new FileOutputStream(obbDest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Build an ACTION_VIEW or ACTION_INSTALL intent depending on the
     * Android version. Uses FileProvider for safe content:// URIs.
     */
    private Intent buildInstallIntent(File apk, String mime) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            uri = Uri.fromFile(apk);
            intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return intent;
    }
}
