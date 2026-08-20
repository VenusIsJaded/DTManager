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

import com.dt.manager.MainActivity;
import com.dt.manager.R;
import com.dt.manager.adapter.FileListAdapter;
import com.dt.manager.core.ApkInspector;
import com.dt.manager.core.ApkInstaller;
import com.dt.manager.util.FileUtils;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.util.Stack;

public class ApkViewerActivity extends AppCompatActivity {

    public static final String EXTRA_APK_PATH = "apk_path";

    private MaterialToolbar toolbar;
    private TextView pathText;
    private TextView summaryText;
    private TextView emptyView;
    private RecyclerView recyclerView;
    private FileListAdapter adapter;
    private FloatingActionButton fabInstall;

    private File apkFile;
    private ApkInspector inspector;
    private String currentDir = "";
    private final Stack<String> history = new Stack<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_viewer);

        toolbar = findViewById(R.id.toolbar);
        pathText = findViewById(R.id.pathText);
        summaryText = findViewById(R.id.summaryText);
        emptyView = findViewById(R.id.emptyView);
        recyclerView = findViewById(R.id.recyclerView);
        fabInstall = findViewById(R.id.fabInstall);

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
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter(this, new FileListAdapter.OnItemClickListener() {
            @Override
            public void onItemClicked(Object item) {
                if (item instanceof ApkInspector.EntryInfo) {
                    handleEntryClick((ApkInspector.EntryInfo) item);
                }
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

        fabInstall.setOnClickListener(v -> {
            new ApkInstaller(this, new ApkInstaller.Callback() {
                @Override public void onSuccess(String message) { Toast.makeText(ApkViewerActivity.this, message, Toast.LENGTH_SHORT).show(); }
                @Override public void onError(String message) { Toast.makeText(ApkViewerActivity.this, getString(R.string.install_fail, message), Toast.LENGTH_LONG).show(); }
            }).install(apkFile);
        });

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
        } else if (id == R.id.action_install) {
            fabInstall.performClick();
            return true;
        } else if (id == R.id.action_extract_all) {
            Toast.makeText(this, R.string.error_not_implemented, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        java.util.List<ApkInspector.EntryInfo> items = inspector.listInDirectory(currentDir);
        adapter.setItems(items);
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

    private void handleEntryClick(ApkInspector.EntryInfo e) {
        if (e.isDirectory()) {
            history.push(currentDir);
            currentDir = e.getPath();
            refresh();
        } else {
            String name = e.getName().toLowerCase();
            if (name.endsWith(".dex")) {
                openDexViewer(e.getPath());
            } else if (name.endsWith(".apk")) {
                // Inner APK inside XAPK/APKM — open nested inspector
                Intent intent = new Intent(this, ApkViewerActivity.class);
                try {
                    File staged = com.dt.manager.util.FileUtils.copyToCache(this,
                            inspector.openStream(e.getPath()), e.getName());
                    intent.putExtra(EXTRA_APK_PATH, staged.getAbsolutePath());
                    startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, R.string.error_no_handler, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openDexViewer(String entryPath) {
        Intent intent = new Intent(this, DexViewerActivity.class);
        intent.putExtra(DexViewerActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath());
        intent.putExtra(DexViewerActivity.EXTRA_DEX_ENTRY, entryPath);
        startActivity(intent);
    }

    private void showEntryOptions(ApkInspector.EntryInfo e) {
        new AlertDialog.Builder(this)
                .setTitle(e.getName())
                .setItems(new CharSequence[]{getString(R.string.action_view)}, (d, w) -> handleEntryClick(e))
                .show();
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
