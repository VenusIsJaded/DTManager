package com.dt.manager.adapter;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.dt.manager.R;
import com.dt.manager.core.ApkInspector;
import com.dt.manager.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClicked(Object item);
        boolean onItemLongClicked(Object item);
    }

    private final Context ctx;
    private final List<Object> items = new ArrayList<>();
    private final OnItemClickListener listener;
    private final LayoutInflater inflater;
    private final Executor iconExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public FileListAdapter(Context ctx, OnItemClickListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setItems(List<?> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.item_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Object o = items.get(position);
        if (o instanceof File) bindFile(h, (File) o);
        else if (o instanceof ApkInspector.EntryInfo) bindEntry(h, (ApkInspector.EntryInfo) o);
    }

    private void bindFile(VH h, File f) {
        h.title.setText(f.getName());
        String date = FileUtils.formatDate(f.lastModified());

        if (f.isDirectory()) {
            int folders = FileUtils.countFolders(f);
            int files = FileUtils.countFiles(f);
            h.subtitle.setText(ctx.getString(R.string.format_summary, folders, files) + "   " + date);
            h.iconBg.setBackgroundResource(R.drawable.bg_icon_default);
            h.icon.setImageResource(R.drawable.ic_folder);
            h.icon.setTag(null);
            h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), android.graphics.PorterDuff.Mode.SRC_ATOP);
        } else {
            h.subtitle.setText(date + "   " + FileUtils.humanReadable(f.length()));
            String name = f.getName().toLowerCase();
            if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
                loadApkIcon(h, f);
            } else {
                applyIconForName(h, f.getName());
            }
        }
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClicked(f);
        });
        h.itemView.setOnLongClickListener(v -> listener != null && listener.onItemLongClicked(f));
    }

    private void bindEntry(VH h, ApkInspector.EntryInfo e) {
        h.title.setText(e.getName());
        if (e.isDirectory()) {
            h.subtitle.setText("");
            h.iconBg.setBackgroundResource(R.drawable.bg_icon_default);
            h.icon.setImageResource(R.drawable.ic_folder);
            h.icon.setTag(null);
            h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), android.graphics.PorterDuff.Mode.SRC_ATOP);
        } else {
            h.subtitle.setText(FileUtils.humanReadable(e.getSize()));
            applyIconForName(h, e.getName());
        }
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClicked(e);
        });
        h.itemView.setOnLongClickListener(v -> listener != null && listener.onItemLongClicked(e));
    }

    private void loadApkIcon(VH h, File apkFile) {
        // Reset to default while async load runs
        h.iconBg.setBackgroundResource(R.drawable.bg_icon_default);
        h.icon.setImageResource(R.drawable.ic_apk);
        h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_green), android.graphics.PorterDuff.Mode.SRC_ATOP);
        final String tag = apkFile.getAbsolutePath() + "#" + h.getBindingAdapterPosition();
        h.icon.setTag(tag);
        iconExecutor.execute(() -> {
            Drawable drawable = null;
            try {
                PackageManager pm = ctx.getPackageManager();
                PackageInfo pkgInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
                if (pkgInfo != null && pkgInfo.applicationInfo != null) {
                    ApplicationInfo ai = pkgInfo.applicationInfo;
                    ai.sourceDir = apkFile.getAbsolutePath();
                    ai.publicSourceDir = apkFile.getAbsolutePath();
                    drawable = pm.getApplicationIcon(ai);
                }
            } catch (Exception ignored) {}
            final Drawable icon = drawable;
            mainHandler.post(() -> {
                if (tag.equals(h.icon.getTag()) && icon != null) {
                    h.icon.setImageDrawable(icon);
                    h.icon.setColorFilter(null);
                }
            });
        });
    }

    private void applyIconForName(VH h, String name) {
        String ext = FileUtils.extensionOf(name);
        switch (ext) {
            case "apk":
                h.icon.setImageResource(R.drawable.ic_apk);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_green), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
            case "dex":
                h.icon.setImageResource(R.drawable.ic_dex);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_green), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
            case "xml":
                h.icon.setImageResource(R.drawable.ic_xml);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_blue), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
            case "arsc":
                h.icon.setImageResource(R.drawable.ic_file);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_orange), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
            case "so":
                h.icon.setImageResource(R.drawable.ic_file);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_purple), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
            case "json":
            case "txt":
            case "properties":
            case "ts":
            case "smali":
            case "md":
            case "yml":
            case "yaml":
            case "ini":
            case "cfg":
            case "csv":
            case "log":
            case "html":
            case "css":
            case "java":
            case "kt":
                h.icon.setImageResource(R.drawable.ic_file);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_blue), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
            case "bin":
                h.icon.setImageResource(R.drawable.ic_file);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
            default:
                h.icon.setImageResource(R.drawable.ic_file);
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), android.graphics.PorterDuff.Mode.SRC_ATOP);
                break;
        }
        h.iconBg.setBackgroundResource(R.drawable.bg_icon_default);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView iconBg;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;

        VH(@NonNull View v) {
            super(v);
            iconBg = v.findViewById(R.id.iconBg);
            icon = v.findViewById(R.id.icon);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }
}
