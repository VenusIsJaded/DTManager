package com.dt.manager.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dt.manager.R;
import com.dt.manager.adapter.FileListAdapter;
import com.dt.manager.core.ApkInspector;
import com.dt.manager.core.ApkInstaller;
import com.dt.manager.util.FileUtils;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

public class ApkViewerActivity extends AppCompatActivity {

    public static final String EXTRA_APK_PATH = "apk_path";

    private MaterialToolbar toolbar;
    private TextView pathText;
    private TextView summaryText;
    private TextView emptyView;
    private RecyclerView recyclerView;
    private FileListAdapter adapter;

    private File apkFile;
    private ApkInspector inspector;
    private String currentDir = "";
    private final Stack<String> history = new Stack<>();

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "txt", "ts", "js", "json", "xml", "smali", "properties", "md",
            "yml", "yaml", "ini", "cfg", "csv", "log", "html", "css", "java", "kt"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_viewer);

        toolbar = findViewById(R.id.toolbar);
        pathText = findViewById(R.id.pathText);
        summaryText = findViewById(R.id.summaryText);
        emptyView = findViewById(R.id.emptyView);
        recyclerView = findViewById(R.id.recyclerView);

        String path = getIntent().getStringExtra(EXTRA_APK_PATH);
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, R.string.error_open_apk, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        apkFile = new File(path);
        try {
            inspector = new ApkInspector(apkFile);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_open_apk) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        toolbar.setTitle(apkFile.getName() + "/");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter(this, new FileListAdapter.OnItemClickListener() {
            @Override
            public void onItemClicked(Object item) {
                handleEntryClick(item);
            }
            @Override
            public boolean onItemLongClicked(Object item) {
                if (item instanceof ApkInspector.EntryInfo) {
                    showEntryOptions((ApkInspector.EntryInfo) item);
                    return true;
                }
                return false;
            }
        });
        recyclerView.setAdapter(adapter);

        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_apk, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_extract_all) {
            Toast.makeText(this, R.string.error_not_implemented, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        java.util.List<ApkInspector.EntryInfo> items = inspector.listInDirectory(currentDir);
        // Prepend ".." parent marker if we have a place to go back to
        java.util.List<Object> display = new java.util.ArrayList<>();
        if (!history.isEmpty()) display.add(com.dt.manager.adapter.FileListAdapter.PARENT_MARKER);
        display.addAll(items);
        adapter.setItems(display);
        String displayPath;
        if (currentDir.isEmpty()) displayPath = apkFile.getName() + "/";
        else displayPath = apkFile.getName() + "/" + currentDir + "/";
        pathText.setText(displayPath);

        int folders = 0;
        int files = 0;
        for (ApkInspector.EntryInfo e : items) {
            if (e.isDirectory()) folders++;
            else files++;
        }
        summaryText.setText(getString(R.string.format_summary, folders, files));
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void handleEntryClick(Object item) {
        if (item == com.dt.manager.adapter.FileListAdapter.PARENT_MARKER) {
            onBackPressed();
            return;
        }
        if (!(item instanceof ApkInspector.EntryInfo)) return;
        ApkInspector.EntryInfo e = (ApkInspector.EntryInfo) item;

        if (e.isDirectory()) {
            history.push(currentDir);
            currentDir = e.getPath();
            refresh();
            return;
        }
        String name = e.getName().toLowerCase();
        if (name.endsWith(".dex")) {
            showDexOptions(e);
        } else if (name.endsWith(".apk")) {
            // Nested APK inside a ZIP/APK — extract to cache and show the APK info dialog
            // (matches the MT Manager behavior: tapping a nested APK shows its details,
            // not auto-opens the viewer).
            try {
                File staged = FileUtils.copyToCache(this,
                        inspector.openStream(e.getPath()), e.getName());
                showNestedApkInfoDialog(staged);
            } catch (Exception ex) {
                Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (isTextEntry(name)) {
            openInTextEditor(e.getPath());
        } else {
            Toast.makeText(this, R.string.error_no_handler, Toast.LENGTH_SHORT).show();
        }
    }

    /** Show the APK info dialog for a nested APK (extracted to cache). */
    private void showNestedApkInfoDialog(File apkFile) {
        com.dt.manager.ui.ApkInfoDialog.show(this, apkFile, new com.dt.manager.ui.ApkInfoDialog.Listener() {
            @Override
            public void onView(File apkFile) {
                Intent intent = new Intent(ApkViewerActivity.this, ApkViewerActivity.class);
                intent.putExtra(EXTRA_APK_PATH, apkFile.getAbsolutePath());
                startActivity(intent);
            }
            @Override
            public void onInstall(File apkFile) {
                new com.dt.manager.core.ApkInstaller(ApkViewerActivity.this, new com.dt.manager.core.ApkInstaller.Callback() {
                    @Override public void onSuccess(String m) { Toast.makeText(ApkViewerActivity.this, m, Toast.LENGTH_SHORT).show(); }
                    @Override public void onError(String m) { Toast.makeText(ApkViewerActivity.this, getString(R.string.install_fail, m), Toast.LENGTH_LONG).show(); }
                }).install(apkFile);
            }
            @Override
            public void onFunctions(File apkFile) {
                String msg = "Path: " + apkFile.getAbsolutePath()
                        + "\nSize: " + FileUtils.humanReadable(apkFile.length());
                new AlertDialog.Builder(ApkViewerActivity.this)
                        .setTitle(apkFile.getName()).setMessage(msg).show();
            }
        });
    }

    /** Show options when tapping a .dex: open in Dex Editor, browse APK contents, or properties. */
    private void showDexOptions(ApkInspector.EntryInfo e) {
        // Find all .dex files in the APK root
        List<String> allDexFiles = findAllDexFiles();

        // If only one dex, just open it
        if (allDexFiles.size() <= 1) {
            openDexViewer(e.getPath(), allDexFiles);
            return;
        }

        // MultiDex chooser — single-choice (radio) items. Previously we used
        // multi-choice with manual unchecking, which caused a ClassCastException
        // when more than one item was tapped (the dialog was androidx, not
        // android.app). Single-choice avoids the crash entirely.
        int tappedIdx = allDexFiles.indexOf(e.getPath());
        if (tappedIdx < 0) tappedIdx = 0;
        final int tappedIdxFinal = tappedIdx;

        CharSequence[] items = allDexFiles.toArray(new CharSequence[0]);
        final int[] selected = { tappedIdxFinal };
        new AlertDialog.Builder(this)
                .setTitle("MultiDex")
                .setSingleChoiceItems(items, tappedIdxFinal, (d, which) -> selected[0] = which)
                .setPositiveButton("OK", (d, which) -> {
                    int sel = selected[0];
                    if (sel < 0 || sel >= allDexFiles.size()) sel = tappedIdxFinal;
                    openDexViewer(allDexFiles.get(sel), allDexFiles);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /** Find all .dex files at the APK root. */
    private List<String> findAllDexFiles() {
        List<String> out = new ArrayList<>();
        for (ApkInspector.EntryInfo e : inspector.listInDirectory("")) {
            if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".dex")) {
                out.add(e.getPath());
            }
        }
        return out;
    }

    private boolean isTextEntry(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0) return false;
        return TEXT_EXT.contains(lowerName.substring(dot + 1));
    }

    private void openInTextEditor(String entryPath) {
        Intent intent = new Intent(this, TextEditorActivity.class);
        intent.putExtra(TextEditorActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath());
        intent.putExtra(TextEditorActivity.EXTRA_ENTRY_PATH, entryPath);
        startActivity(intent);
    }

    private void openDexViewer(String entryPath, List<String> allDexFiles) {
        Intent intent = new Intent(this, DexViewerActivity.class);
        intent.putExtra(DexViewerActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath());
        intent.putExtra(DexViewerActivity.EXTRA_DEX_ENTRY, entryPath);
        if (allDexFiles != null && !allDexFiles.isEmpty()) {
            intent.putExtra(DexViewerActivity.EXTRA_DEX_ENTRIES, new ArrayList<>(allDexFiles));
        }
        startActivity(intent);
    }

    /** Context-aware long-press — only relevant actions per file type. */
    private void showEntryOptions(ApkInspector.EntryInfo e) {
        if (e.isDirectory()) {
            new AlertDialog.Builder(this)
                    .setTitle(e.getName())
                    .setMessage("Folder")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();

        String name = e.getName().toLowerCase();
        if (name.endsWith(".dex")) {
            labels.add("Open in Dex Editor");
            actions.add(() -> showDexOptions(e));
        } else if (name.endsWith(".apk")) {
            labels.add("Install");
            actions.add(() -> {
                try {
                    File staged = FileUtils.copyToCache(this,
                            inspector.openStream(e.getPath()), e.getName());
                    new ApkInstaller(this, new ApkInstaller.Callback() {
                        @Override public void onSuccess(String m) { Toast.makeText(ApkViewerActivity.this, m, Toast.LENGTH_SHORT).show(); }
                        @Override public void onError(String m) { Toast.makeText(ApkViewerActivity.this, getString(R.string.install_fail, m), Toast.LENGTH_LONG).show(); }
                    }).install(staged);
                } catch (Exception ex) {
                    Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        } else if (isTextEntry(name)) {
            labels.add("Edit");
            actions.add(() -> openInTextEditor(e.getPath()));
        }

        labels.add("Properties");
        actions.add(() -> showEntryProperties(e));

        CharSequence[] arr = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(e.getName())
                .setItems(arr, (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .show();
    }

    private void showEntryProperties(ApkInspector.EntryInfo e) {
        String msg = "Path: " + e.getPath()
                + "\nSize: " + FileUtils.humanReadable(e.getSize())
                + "\nModified: " + FileUtils.formatDate(e.getTime());
        new AlertDialog.Builder(this).setTitle(e.getName()).setMessage(msg).show();
    }

    @Override
    public void onBackPressed() {
        if (!history.isEmpty()) {
            currentDir = history.pop();
            refresh();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (inspector != null) {
            try { inspector.close(); } catch (Exception ignored) {}
        }
    }
}
