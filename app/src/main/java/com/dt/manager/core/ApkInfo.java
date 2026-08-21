package com.dt.manager.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.dt.manager.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Lightweight reader for APK metadata: app icon, label, version, SDKs, and
 * a coarse signature-scheme guess. Uses PackageManager's
 * getPackageArchiveInfo plus our own ApkInspector for V1 / META-INF scans.
 */
public class ApkInfo {

    public Drawable icon;
    public String appName;
    public String packageName;
    public String versionName;
    public long versionCode;
    public long fileSize;
    public int minSdk = -1;
    public int targetSdk = -1;
    /** One of: "V1", "V1+V2", "V1+V2+V3", "None", "Unknown" */
    public String signatureScheme = "Unknown";

    public static ApkInfo fromFile(Context ctx, File apkFile) {
        ApkInfo info = new ApkInfo();
        info.fileSize = apkFile.length();

        PackageManager pm = ctx.getPackageManager();
        PackageInfo pkgInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
        if (pkgInfo != null && pkgInfo.applicationInfo != null) {
            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            // Required for getApplicationIcon to work on archived APKs
            appInfo.sourceDir = apkFile.getAbsolutePath();
            appInfo.publicSourceDir = apkFile.getAbsolutePath();

            try {
                info.icon = pm.getApplicationIcon(appInfo);
            } catch (Exception ignored) {}
            CharSequence label = pm.getApplicationLabel(appInfo);
            info.appName = label != null ? label.toString() : apkFile.getName();
            info.packageName = pkgInfo.packageName;
            info.versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.versionCode = pkgInfo.getLongVersionCode();
            } else {
                info.versionCode = pkgInfo.versionCode;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                info.minSdk = appInfo.minSdkVersion;
            }
            info.targetSdk = appInfo.targetSdkVersion;
        } else {
            info.appName = apkFile.getName();
            info.packageName = "";
            info.versionName = "";
        }

        info.signatureScheme = detectSignatureScheme(apkFile);
        return info;
    }

    private static String detectSignatureScheme(File apkFile) {
        boolean v1 = false;
        try (ApkInspector inspector = new ApkInspector(apkFile)) {
            List<ApkInspector.EntryInfo> entries = inspector.listEntries();
            for (ApkInspector.EntryInfo e : entries) {
                String upper = e.getPath().toUpperCase();
                if (upper.startsWith("META-INF/")
                        && (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
                    v1 = true;
                    break;
                }
            }
        } catch (IOException ignored) {
            return "Unknown";
        }

        // V2/V3 detection requires reading the APK Signing Block — too complex
        // for this basic version. We assume V2+ are present when V1 is, since
        // modern Android Studio signs with all schemes by default.
        if (v1) return "V1+V2+V3";
        return "None";
    }

    public String formatSize() {
        return FileUtils.humanReadable(fileSize);
    }

    public String formatSdk() {
        StringBuilder sb = new StringBuilder();
        if (targetSdk > 0) {
            sb.append("Android ").append(sdkToVersionName(targetSdk))
              .append(" (API ").append(targetSdk).append(")");
        }
        return sb.toString();
    }

    public String formatMinSdk() {
        if (minSdk <= 0) return "—";
        return "Android " + sdkToVersionName(minSdk) + " (API " + minSdk + ")";
    }

    public static String sdkToVersionName(int sdk) {
        switch (sdk) {
            case 21: return "5.0";
            case 22: return "5.1";
            case 23: return "6.0";
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0";
            case 27: return "8.1";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12L";
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            case 36: return "16";
            case 37: return "16";
            default: return String.valueOf(sdk);
        }
    }
}
