package com.dt.manager.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.dt.manager.R;
import com.dt.manager.core.BinaryXmlDecoder;
import com.dt.manager.core.SyntaxHighlighter;
import com.dt.manager.util.FileUtils;
import com.dt.manager.widget.CodeEditorView;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Plain-text editor for any file inside (or outside) an APK.
 *
 * Sources:
 *   1. EXTRA_FILE_PATH — a real file on disk (read & overwrite in place).
 *   2. EXTRA_APK_PATH + EXTRA_ENTRY_PATH — a file inside an APK. Extracted
 *      to cache on open. On save, written back to the cache copy and the
 *      user is told the APK itself was not modified.
 *
 * Binary XML (AndroidManifest.xml, *.xml resources inside an APK) is
 * detected via magic bytes and decoded to text XML before display.
 *
 * The editor uses CodeEditorView which provides:
 *   - Line-number gutter on the left (synchronized with vertical scroll)
 *   - Monospace font + comfortable line height
 *   - Syntax highlighting (XML/JSON/Smali/Java) with the MT Manager palette
 *   - Horizontal scroll for long lines
 */
public class TextEditorActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_ENTRY_PATH = "entry_path";

    private MaterialToolbar toolbar;
    private CodeEditorView editor;
    private TextView status;

    private File workingFile;
    private boolean dirty = false;
    private boolean fromApk = false;
    private boolean wasBinaryXml = false;

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
            byte[] raw = readAllBytes(in);
            String text = decodeBytes(raw);
            editor.setLanguage(SyntaxHighlighter.detectLanguage(workingFile.getName()));
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
        // Cache key: hash of (apk absolute path + entry path) so each entry
        // from each APK gets its own cache file that survives across sessions.
        // This is what makes edits persist when re-opening the entry.
        String key = Integer.toHexString((apkPath + "#" + entryPath).hashCode());
        File cacheDir = new File(getCacheDir(), "apk_edits");
        cacheDir.mkdirs();
        File staged = new File(cacheDir, key + "_" + new File(entryPath).getName());

        if (!staged.exists()) {
            // First time opening — extract from APK
            try (com.dt.manager.core.ApkInspector inspector =
                         new com.dt.manager.core.ApkInspector(new File(apkPath))) {
                InputStream in = inspector.openStream(entryPath);
                byte[] raw = readAllBytes(in);
                in.close();
                String text = decodeBytes(raw);
                try (FileOutputStream out = new FileOutputStream(staged)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Toast.makeText(this, "Failed to open entry: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        workingFile = staged;
        try (FileInputStream in = new FileInputStream(staged)) {
            byte[] raw = readAllBytes(in);
            String text = new String(raw, StandardCharsets.UTF_8);
            editor.setLanguage(SyntaxHighlighter.detectLanguage(new File(entryPath).getName()));
            editor.setText(text);
            dirty = false;
            updateStatus();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to load cached copy: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** Detect binary XML and decode it; otherwise treat as UTF-8 text. */
    private String decodeBytes(byte[] raw) {
        if (BinaryXmlDecoder.isBinaryXml(raw)) {
            wasBinaryXml = true;
            String decoded = BinaryXmlDecoder.decode(raw);
            return decoded != null ? decoded : "";
        }
        // Strip BOM if present
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
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
        if (wasBinaryXml) {
            sb.append("Decoded from binary XML — saved as text");
        } else if (fromApk) {
            sb.append("Cached copy — saving writes to cache, not the APK");
        }
        if (dirty) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append("Unsaved changes");
        } else if (sb.length() == 0) {
            sb.append("Saved");
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
                new AlertDialog.Builder(this)
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
        } else if (id == R.id.action_save_to_apk) {
            saveToApk();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Save the current edit back into the APK file on disk by repacking + re-signing. */
    private void saveToApk() {
        if (!fromApk) {
            // For files on disk, just save normally
            save();
            return;
        }
        String apkPath = getIntent().getStringExtra(EXTRA_APK_PATH);
        String entryPath = getIntent().getStringExtra(EXTRA_ENTRY_PATH);
        if (apkPath == null || entryPath == null) {
            Toast.makeText(this, "Cannot repack — missing APK info", Toast.LENGTH_LONG).show();
            return;
        }

        // First, save the current editor content to the cache file
        save();

        final File apkFile = new File(apkPath);
        final Map<String, byte[]> modifiedEntries = new java.util.HashMap<>();
        try {
            modifiedEntries.put(entryPath, editor.getText().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Toast.makeText(this, "Encode failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        // Warn if the original was binary XML — saving as text may break the APK
        Runnable doRepack = () -> runRepack(apkFile, modifiedEntries, entryPath);
        if (wasBinaryXml) {
            new AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage(R.string.repack_warn_binary_xml)
                    .setPositiveButton("Continue", (d, w) -> doRepack.run())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            doRepack.run();
        }
    }

    private void runRepack(File apkFile, Map<String, byte[]> modifiedEntries, String entryPath) {
        final androidx.appcompat.app.AlertDialog progress = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.repack_progress)
                .setMessage("Starting...")
                .setCancelable(false)
                .show();

        com.dt.manager.core.ApkRepacker.repack(this, apkFile, modifiedEntries,
                new com.dt.manager.core.ApkRepacker.ProgressListener() {
                    @Override
                    public void onProgress(String message) {
                        runOnUiThread(() -> {
                            if (progress.isShowing()) {
                                progress.setMessage(message);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(File repackedApk) {
                        runOnUiThread(() -> {
                            progress.dismiss();
                            // Delete the cached edit file so next open loads from the repacked APK
                            if (workingFile != null) workingFile.delete();
                            Toast.makeText(TextEditorActivity.this,
                                    R.string.repack_success, Toast.LENGTH_SHORT).show();
                            dirty = false;
                            updateStatus();
                            // Update the apkPath intent extra to point to the new APK
                            // (same path on disk, but ensures re-open reads fresh content)
                            getIntent().putExtra(EXTRA_APK_PATH, repackedApk.getAbsolutePath());
                            fromApk = true;
                            // Reload from the repacked APK so editor reflects what's actually in the APK now
                            // (in case the editor's view of the content was diverging)
                            // Re-extract by clearing the cache and reopening
                            loadFromApk(repackedApk.getAbsolutePath(), entryPath);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            progress.dismiss();
                            Toast.makeText(TextEditorActivity.this,
                                    getString(R.string.repack_fail, message),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            new AlertDialog.Builder(this)
                    .setTitle("Discard changes?")
                    .setPositiveButton("Discard", (d, w) -> super.onBackPressed())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }
}
