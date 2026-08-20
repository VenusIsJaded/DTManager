package com.dt.manager;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dt.manager.adapter.FileListAdapter;
import com.dt.manager.ui.AboutActivity;
import com.dt.manager.ui.ApkViewerActivity;
import com.dt.manager.util.FileUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.util.Stack;

public class MainActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private TextView pathText;
    private TextView summaryText;
    private TextView emptyView;
    private RecyclerView recyclerView;
    private FileListAdapter adapter;
    private FloatingActionButton fab;

    private File currentDir;
    private final Stack<File> history = new Stack<>();

    private final ActivityResultLauncher<String> storagePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) refresh();
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tryOpenAllFilesAccess();
            });

    private final ActivityResultLauncher<Intent> allFilesLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    refresh();
                } else {
                    Toast.makeText(this, R.string.grant_storage_perm, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        pathText = findViewById(R.id.pathText);
        summaryText = findViewById(R.id.summaryText);
        emptyView = findViewById(R.id.emptyView);
        recyclerView = findViewById(R.id.recyclerView);
        fab = findViewById(R.id.fab);

        setSupportActionBar(toolbar);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter(this, new FileListAdapter.OnItemClickListener() {
            @Override
            public void onItemClicked(Object item) {
                if (item instanceof File) handleFileClick((File) item);
            }
            @Override
            public boolean onItemLongClicked(Object item) {
                if (item instanceof File) {
                    showFileOptions((File) item);
                    return true;
                }
                return false;
            }
        });
        recyclerView.setAdapter(adapter);

        fab.setOnClickListener(v -> refresh());

        if (!checkStoragePermission()) {
            requestStoragePermission();
        } else {
            currentDir = FileUtils.getRootStoragePath(this);
            refresh();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            refresh();
            return true;
        } else if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        } else if (id == R.id.action_search) {
            Toast.makeText(this, R.string.error_not_implemented, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tryOpenAllFilesAccess();
        } else {
            storagePermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void tryOpenAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.grant_storage_perm)
                    .setMessage(R.string.grant_storage_perm)
                    .setPositiveButton(R.string.grant, (d, w) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            allFilesLauncher.launch(intent);
                        } catch (Exception e) {
                            Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            allFilesLauncher.launch(fallback);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void refresh() {
        if (currentDir == null) currentDir = FileUtils.getRootStoragePath(this);
        java.util.List<File> items = FileUtils.listFiles(currentDir);
        adapter.setItems(items);
        pathText.setText(currentDir.getAbsolutePath() + (currentDir.isDirectory() ? "/" : ""));

        int folders = FileUtils.countFolders(currentDir);
        int files = FileUtils.countFiles(currentDir);
        String disk = FileUtils.diskSummary(currentDir);
        if (disk.isEmpty()) {
            summaryText.setText(getString(R.string.format_summary, folders, files));
        } else {
            summaryText.setText(getString(R.string.format_summary_disk, folders, files, disk.split("/")[0], disk.split("/")[1]));
        }
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void handleFileClick(File f) {
        if (f.isDirectory()) {
            history.push(currentDir);
            currentDir = f;
            refresh();
        } else {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
                Intent intent = new Intent(this, ApkViewerActivity.class);
                intent.putExtra(ApkViewerActivity.EXTRA_APK_PATH, f.getAbsolutePath());
                startActivity(intent);
            } else {
                Toast.makeText(this, R.string.error_no_handler, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showFileOptions(File f) {
        String[] options = {getString(R.string.action_install), getString(R.string.action_view), getString(R.string.action_properties)};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (f.getName().toLowerCase().endsWith(".apk")
                                    || f.getName().toLowerCase().endsWith(".xapk")
                                    || f.getName().toLowerCase().endsWith(".apkm")) {
                                new com.dt.manager.core.ApkInstaller(this, new com.dt.manager.core.ApkInstaller.Callback() {
                                    @Override public void onSuccess(String message) { Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show(); }
                                    @Override public void onError(String message) { Toast.makeText(MainActivity.this, getString(R.string.install_fail, message), Toast.LENGTH_LONG).show(); }
                                }).install(f);
                            } else {
                                Toast.makeText(this, R.string.error_no_handler, Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case 1:
                            handleFileClick(f);
                            break;
                        case 2:
                            showProperties(f);
                            break;
                    }
                })
                .show();
    }

    private void showProperties(File f) {
        String msg = "Path: " + f.getAbsolutePath()
                + "\nSize: " + FileUtils.humanReadable(f.length())
                + "\nModified: " + FileUtils.formatDate(f.lastModified())
                + "\nReadable: " + f.canRead()
                + "\nWritable: " + f.canWrite();
        new AlertDialog.Builder(this).setTitle(f.getName()).setMessage(msg).show();
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
}
