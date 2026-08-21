package com.dt.manager;

import android.Manifest;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.dt.manager.adapter.FileListAdapter;
import com.dt.manager.core.ApkInstaller;
import com.dt.manager.core.FileClipboard;
import com.dt.manager.core.FilePaneController;
import com.dt.manager.ui.AboutActivity;
import com.dt.manager.ui.ApkInfoDialog;
import com.dt.manager.ui.ApkViewerActivity;
import com.dt.manager.ui.TextEditorActivity;
import com.dt.manager.util.FileUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private TextView pathText;
    private TextView summaryText;
    private FrameLayout paneLeftWrap, paneRightWrap;
    private FloatingActionButton fab;

    private FilePaneController leftPane, rightPane;
    private FilePaneController activePane;

    private final ActivityResultLauncher<String> storagePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) initPanes();
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tryOpenAllFilesAccess();
            });

    private final ActivityResultLauncher<Intent> allFilesLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    initPanes();
                } else {
                    Toast.makeText(this, R.string.grant_storage_perm, Toast.LENGTH_SHORT).show();
                }
            });

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "txt", "ts", "js", "json", "xml", "smali", "properties", "md",
            "yml", "yaml", "ini", "cfg", "csv", "log", "html", "css", "java", "kt"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        pathText = findViewById(R.id.pathText);
        summaryText = findViewById(R.id.summaryText);
        paneLeftWrap = findViewById(R.id.paneLeftWrap);
        paneRightWrap = findViewById(R.id.paneRightWrap);
        fab = findViewById(R.id.fab);

        setSupportActionBar(toolbar);

        FileListAdapter.OnItemClickListener listener = new FileListAdapter.OnItemClickListener() {
            @Override
            public void onItemClicked(Object item) {
                if (item == FileListAdapter.PARENT_MARKER) {
                    if (activePane != null && activePane.goBack()) updateHeaderForActivePane();
                    return;
                }
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
        };

        FilePaneController.OnPaneNavigateListener navListener = new FilePaneController.OnPaneNavigateListener() {
            @Override public void onPaneActivated(FilePaneController pane) { setActivePane(pane); }
            @Override public void onPaneContentChanged(FilePaneController pane) {
                if (pane == activePane) updateHeaderForActivePane();
            }
        };

        View leftRoot = findViewById(R.id.paneLeft);
        View rightRoot = findViewById(R.id.paneRight);
        leftPane = new FilePaneController(this, leftRoot, listener, navListener);
        rightPane = new FilePaneController(this, rightRoot, listener, navListener);

        fab.setOnClickListener(v -> {
            if (activePane != null) activePane.refresh();
        });

        if (!checkStoragePermission()) {
            requestStoragePermission();
        } else {
            initPanes();
        }
    }

    private void initPanes() {
        File root = FileUtils.getRootStoragePath(this);
        leftPane.setRoot(root);
        rightPane.setRoot(root);
        setActivePane(leftPane);
    }

    private void setActivePane(FilePaneController pane) {
        activePane = pane;
        paneLeftWrap.setBackgroundColor(
                ContextCompat.getColor(this, pane == leftPane ? R.color.bg_tertiary : R.color.bg_primary));
        paneRightWrap.setBackgroundColor(
                ContextCompat.getColor(this, pane == rightPane ? R.color.bg_tertiary : R.color.bg_primary));
        updateHeaderForActivePane();
    }

    private void updateHeaderForActivePane() {
        if (activePane == null || activePane.getCurrentDir() == null) return;
        File cur = activePane.getCurrentDir();
        pathText.setText(cur.getAbsolutePath() + "/");
        int folders = FileUtils.countFolders(cur);
        int files = FileUtils.countFiles(cur);
        String disk = FileUtils.diskSummary(cur);
        if (disk.isEmpty()) {
            summaryText.setText(getString(R.string.format_summary, folders, files));
        } else {
            String[] parts = disk.split("/");
            if (parts.length == 2) {
                summaryText.setText(getString(R.string.format_summary_disk, folders, files, parts[0], parts[1]));
            } else {
                summaryText.setText(getString(R.string.format_summary, folders, files));
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem pasteItem = menu.findItem(R.id.action_paste);
        if (pasteItem != null) {
            pasteItem.setVisible(!FileClipboard.getInstance().isEmpty());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            if (activePane != null) activePane.refresh();
            return true;
        } else if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        } else if (id == R.id.action_search) {
            showSearchDialog();
            return true;
        } else if (id == R.id.action_paste) {
            pasteIntoActivePane();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSearchDialog() {
        if (activePane == null) return;
        final EditText et = new EditText(this);
        et.setHint("Filter by name (case-insensitive)");
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_search)
                .setView(et)
                .setPositiveButton(android.R.string.search_go, (d, w) -> {
                    String q = et.getText().toString().trim();
                    activePane.setQuery(q.toLowerCase());
                    updateHeaderForActivePane();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    private void handleFileClick(File f) {
        if (activePane == null) setActivePane(leftPane);
        if (f.isDirectory()) {
            activePane.navigateTo(f);
            updateHeaderForActivePane();
        } else {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
                showApkInfoDialog(f);
            } else if (isTextFile(name)) {
                openTextEditor(f);
            } else {
                Toast.makeText(this, R.string.error_no_handler, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean isTextFile(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0) return false;
        return TEXT_EXT.contains(lowerName.substring(dot + 1));
    }

    private void openTextEditor(File file) {
        Intent intent = new Intent(this, TextEditorActivity.class);
        intent.putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    private void showApkInfoDialog(File apkFile) {
        ApkInfoDialog.show(this, apkFile, new ApkInfoDialog.Listener() {
            @Override
            public void onView(File apkFile) {
                Intent intent = new Intent(MainActivity.this, ApkViewerActivity.class);
                intent.putExtra(ApkViewerActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath());
                startActivity(intent);
            }
            @Override
            public void onInstall(File apkFile) {
                new ApkInstaller(MainActivity.this, new ApkInstaller.Callback() {
                    @Override public void onSuccess(String message) { Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show(); }
                    @Override public void onError(String message) { Toast.makeText(MainActivity.this, getString(R.string.install_fail, message), Toast.LENGTH_LONG).show(); }
                }).install(apkFile);
            }
            @Override
            public void onFunctions(File apkFile) {
                showFunctionsMenu(apkFile);
            }
        });
    }

    private void showFunctionsMenu(File apkFile) {
        new AlertDialog.Builder(this)
                .setTitle(apkFile.getName())
                .setItems(new CharSequence[]{"Install", "View inside", "Properties"}, (d, which) -> {
                    switch (which) {
                        case 0:
                            new ApkInstaller(this, new ApkInstaller.Callback() {
                                @Override public void onSuccess(String m) { Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show(); }
                                @Override public void onError(String m) { Toast.makeText(MainActivity.this, getString(R.string.install_fail, m), Toast.LENGTH_LONG).show(); }
                            }).install(apkFile);
                            break;
                        case 1:
                            Intent intent = new Intent(this, ApkViewerActivity.class);
                            intent.putExtra(ApkViewerActivity.EXTRA_APK_PATH, apkFile.getAbsolutePath());
                            startActivity(intent);
                            break;
                        case 2:
                            showProperties(apkFile);
                            break;
                    }
                })
                .show();
    }

    /** Context-aware long-press menu — Install only for APK/XAPK/APKM, never for folders. */
    private void showFileOptions(File f) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();

        // Clipboard actions: available for any file or folder
        labels.add(getString(R.string.action_copy));
        actions.add(() -> {
            FileClipboard.getInstance().set(f, FileClipboard.Action.COPY);
            Toast.makeText(this, "Copied: " + f.getName(), Toast.LENGTH_SHORT).show();
            invalidateOptionsMenu();
        });
        labels.add(getString(R.string.action_cut));
        actions.add(() -> {
            FileClipboard.getInstance().set(f, FileClipboard.Action.CUT);
            Toast.makeText(this, "Cut: " + f.getName(), Toast.LENGTH_SHORT).show();
            invalidateOptionsMenu();
        });

        if (f.isDirectory()) {
            labels.add(getString(R.string.action_properties));
            actions.add(() -> showProperties(f));
        } else {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
                labels.add(getString(R.string.action_details));
                actions.add(() -> showApkInfoDialog(f));
                labels.add(getString(R.string.action_install));
                actions.add(() -> new ApkInstaller(this, new ApkInstaller.Callback() {
                    @Override public void onSuccess(String m) { Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show(); }
                    @Override public void onError(String m) { Toast.makeText(MainActivity.this, getString(R.string.install_fail, m), Toast.LENGTH_LONG).show(); }
                }).install(f));
            } else if (isTextFile(name)) {
                labels.add(getString(R.string.action_save).equals("Save") ? "Edit" : "Edit");
                actions.add(() -> openTextEditor(f));
            }
            labels.add(getString(R.string.action_properties));
            actions.add(() -> showProperties(f));
        }

        CharSequence[] arr = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(arr, (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .show();
    }

    private void pasteIntoActivePane() {
        FileClipboard clip = FileClipboard.getInstance();
        if (clip.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (activePane == null || activePane.getCurrentDir() == null) return;
        File src = clip.getSource();
        File destDir = activePane.getCurrentDir();

        // Refuse to paste a folder into itself or its descendant
        if (src.isDirectory() && destDir.getAbsolutePath().startsWith(src.getAbsolutePath())) {
            Toast.makeText(this, getString(R.string.paste_fail, "cannot paste folder into itself"), Toast.LENGTH_LONG).show();
            return;
        }

        final File source = src;
        final File targetDir = destDir;
        final boolean isCut = clip.getAction() == FileClipboard.Action.CUT;

        new Thread(() -> {
            try {
                File result = FileUtils.copy(source, targetDir);
                if (isCut && !source.getAbsolutePath().equals(result.getAbsolutePath())) {
                    FileUtils.deleteRecursive(source);
                }
                runOnUiThread(() -> {
                    if (isCut) clip.clear();
                    Toast.makeText(this, getString(R.string.paste_done, result.getName()), Toast.LENGTH_SHORT).show();
                    activePane.refresh();
                    invalidateOptionsMenu();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.paste_fail, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }).start();
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
        if (activePane != null && activePane.goBack()) {
            updateHeaderForActivePane();
            return;
        }
        super.onBackPressed();
    }
}
