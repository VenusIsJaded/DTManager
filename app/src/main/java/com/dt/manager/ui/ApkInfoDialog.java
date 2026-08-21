package com.dt.manager.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.dt.manager.R;
import com.dt.manager.core.ApkInfo;

import java.io.File;

/**
 * Modal dialog showing APK metadata with three actions:
 * FUNCTION, VIEW, INSTALL — matches the MT Manager reference screenshot.
 */
public class ApkInfoDialog {

    public interface Listener {
        void onView(File apkFile);
        void onInstall(File apkFile);
        void onFunctions(File apkFile);
    }

    public static void show(@NonNull AppCompatActivity activity, @NonNull File apkFile, Listener listener) {
        Context ctx = activity.getApplicationContext();
        LayoutInflater inflater = LayoutInflater.from(activity);

        ApkInfo info = ApkInfo.fromFile(ctx, apkFile);

        View content = inflater.inflate(R.layout.dialog_apk_info, null, false);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);

        ImageView iconApp = content.findViewById(R.id.iconApp);
        TextView textAppName = content.findViewById(R.id.textAppName);
        TextView textVersion = content.findViewById(R.id.textVersion);

        if (info.icon != null) {
            iconApp.setImageDrawable(info.icon);
        } else {
            iconApp.setImageResource(R.drawable.ic_apk);
        }
        textAppName.setText(info.appName != null ? info.appName : apkFile.getName());
        textVersion.setText("Version " + (info.versionName != null ? info.versionName : ""));

        setRow(content, R.id.rowPackage, "Package name", info.packageName);
        setRow(content, R.id.rowVersionCode, "Version code", String.valueOf(info.versionCode));
        setRow(content, R.id.rowFileSize, "File size", info.formatSize());
        setRow(content, R.id.rowSignature, "Signature", info.signatureScheme);
        setRow(content, R.id.rowTargetSdk, "Target SDK", info.formatSdk());
        setRow(content, R.id.rowMinSdk, "Minimum SDK", info.formatMinSdk());

        TextView btnFunctions = content.findViewById(R.id.btnFunctions);
        TextView btnView = content.findViewById(R.id.btnView);
        TextView btnInstall = content.findViewById(R.id.btnInstall);

        btnFunctions.setOnClickListener(v -> {
            if (listener != null) listener.onFunctions(apkFile);
            dialog.dismiss();
        });
        btnView.setOnClickListener(v -> {
            if (listener != null) listener.onView(apkFile);
            dialog.dismiss();
        });
        btnInstall.setOnClickListener(v -> {
            if (listener != null) listener.onInstall(apkFile);
            dialog.dismiss();
        });

        dialog.show();
        // Make dialog fill width
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void setRow(View root, int rowId, String label, String value) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        TextView l = row.findViewById(R.id.label);
        TextView v = row.findViewById(R.id.value);
        if (l != null) l.setText(label);
        if (v != null) v.setText(value != null && !value.isEmpty() ? value : "—");
    }
}
