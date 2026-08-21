package com.dt.manager.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.dt.manager.R;
import com.dt.manager.util.FileUtils;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Plain-text editor for any file inside (or outside) an APK.
 *
 * Sources:
 *   1. EXTRA_FILE_PATH — a real file on disk (read & overwrite in place).
 *   2. EXTRA_APK_PATH + EXTRA_ENTRY_PATH — a file inside an APK. Extracted
 *      to cache on open. On save, written back to the cache copy and the
 *      user is told the APK itself was not modified.
 *
 * Supported extensions: .txt, .ts, .js, .json, .xml, .smali, .properties,
 * .md, .cfg, .ini, .yml, .yaml, .csv, .log, .html, .css, .java, .kt.
 */
public class TextEditorActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_ENTRY_PATH = "entry_path";

    private MaterialToolbar toolbar;
    private EditText editor;
    private TextView status;

    private File workingFile;
    private boolean dirty = false;
    private boolean fromApk = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_editor);

        toolbar = findViewById(R.id.toolbar);
        editor = findViewById(R.id.editor);
        status = findViewById(R.id.status);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        String apkPath = getIntent().getStringExtra(EXTRA_APK_PATH);
        String entryPath = getIntent().getStringExtra(EXTRA_ENTRY_PATH);

        if (filePath != null) {
            workingFile = new File(filePath);
            toolbar.setTitle(workingFile.getName());
            loadFromDisk();
        } else if (apkPath != null && entryPath != null) {
            toolbar.setTitle(new File(entryPath).getName());
            loadFromApk(apkPath, entryPath);
        } else {
            Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                dirty = true;
                updateStatus();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadFromDisk() {
        try (FileInputStream in = new FileInputStream(workingFile)) {
            String text = readText(in);
            editor.setText(text);
            dirty = false;
            updateStatus();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to load: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void loadFromApk(String apkPath, String entryPath) {
        fromApk = true;
        try (com.dt.manager.core.ApkInspector inspector =
                     new com.dt.manager.core.ApkInspector(new File(apkPath))) {
            InputStream in = inspector.openStream(entryPath);
            String text = readText(in);
            in.close();
            // Stage to cache so we can "save" (to cache — APK repackage not supported yet)
            File staged = new File(getCacheDir(), new File(entryPath).getName());
            try (FileOutputStream out = new FileOutputStream(staged)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            workingFile = staged;
            editor.setText(text);
            dirty = false;
            updateStatus();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to open entry: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private String readText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        byte[] data = out.toByteArray();
        // Strip BOM if present
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private void save() {
        if (workingFile == null) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(workingFile)) {
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
            updateStatus();
            Toast.makeText(this, "Saved to " + workingFile.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateStatus() {
        if (status == null) return;
        StringBuilder sb = new StringBuilder();
        if (fromApk) sb.append("Cached copy — saving writes to cache, not the APK");
        else if (dirty) sb.append("Unsaved changes");
        else sb.append("Saved");
        if (fromApk && dirty) {
            sb = new StringBuilder("Cached copy — Unsaved changes");
        }
        status.setText(sb.toString());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_editor, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (dirty) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Discard changes?")
                        .setPositiveButton("Discard", (d, w) -> finish())
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
            finish();
            return true;
        } else if (id == R.id.action_save) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Discard changes?")
                    .setPositiveButton("Discard", (d, w) -> super.onBackPressed())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }
}
