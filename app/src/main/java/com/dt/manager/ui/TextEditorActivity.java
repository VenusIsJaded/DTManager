package com.dt.manager.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.dt.manager.core.ApkRepacker;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Plain-text editor for any file inside (or outside) an APK.
 *
 * Flow (matches MT Manager):
 *   1. Open file from APK — extracted to cache.
 *   2. Edit — auto-saves to cache (debounced 500ms).
 *   3. Tap Save button — forces immediate save to cache.
 *   4. Tap back — if any edits were made this session, prompt
 *      "Auto-sign APK?"
 *        Yes → repack APK (preserve compression methods, rebuild V1
 *               signature with DT Manager debug key) and replace the
 *               APK on disk.
 *        No  → exit without repacking. Cache is preserved so re-opening
 *              shows the edited content; APK on disk is unchanged.
 *
 * Binary XML (AndroidManifest.xml, *.xml resources) is detected and
 * decoded to text on open. Saving as text breaks the APK — a warning
 * dialog is shown before repack.
 */
public class TextEditorActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_ENTRY_PATH = "entry_path";

    private static final long AUTO_SAVE_DELAY_MS = 500;

    private MaterialToolbar toolbar;
    private CodeEditorView editor;
    private TextView status;

    private File workingFile;
    private boolean dirty = false;          // changes since last save
    private boolean wasModified = false;    // any changes made this session
    private boolean fromApk = false;
    private boolean wasBinaryXml = false;

    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSaveRunnable = this::autoSave;

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
                wasModified = true;
                updateStatus();
                // Schedule debounced auto-save
                autoSaveHandler.removeCallbacks(autoSaveRunnable);
                autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
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
            wasModified = false;
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
            wasModified = false;
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

    /** Auto-save (called by debounced runnable). Silently writes cache file. */
    private void autoSave() {
        if (workingFile == null || !dirty) return;
        try (FileOutputStream out = new FileOutputStream(workingFile)) {
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
            updateStatus();
        } catch (IOException ignored) {
            // will retry on next change
        }
    }

    /** Explicit save (called by Save button). Cancels pending auto-save. */
    private void save() {
        if (workingFile == null) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show();
            return;
        }
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        try (FileOutputStream out = new FileOutputStream(workingFile)) {
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
            updateStatus();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateStatus() {
        if (status == null) return;
        StringBuilder sb = new StringBuilder();
        if (wasBinaryXml) {
            sb.append("Decoded from binary XML");
        } else if (fromApk) {
            sb.append("Cached copy");
        }
        if (dirty) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append("Unsaved changes");
        } else if (sb.length() == 0) {
            sb.append("Saved");
        } else {
            sb.append(" — auto-saved");
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
            onBackPressed();
            return true;
        } else if (id == R.id.action_save) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // Cancel any pending auto-save and force-save now
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (dirty) autoSave();

        if (wasModified && fromApk) {
            // Prompt: auto-sign APK?
            promptAutoSign();
        } else {
            super.onBackPressed();
        }
    }

    private void promptAutoSign() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.auto_sign_title)
                .setMessage(R.string.auto_sign_message)
                .setPositiveButton(R.string.auto_sign_yes, (d, w) -> startRepack())
                .setNegativeButton(R.string.auto_sign_no, (d, w) -> finish())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void startRepack() {
        String apkPath = getIntent().getStringExtra(EXTRA_APK_PATH);
        String entryPath = getIntent().getStringExtra(EXTRA_ENTRY_PATH);
        if (apkPath == null || entryPath == null) {
            finish();
            return;
        }

        // Warn if binary XML — saving as text will break the APK
        if (wasBinaryXml) {
            new AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage(R.string.repack_warn_binary_xml)
                    .setPositiveButton("Continue", (d, w) -> doRepack(apkPath, entryPath))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            doRepack(apkPath, entryPath);
        }
    }

    private void doRepack(String apkPath, String entryPath) {
        final File apkFile = new File(apkPath);
        final Map<String, byte[]> modifiedEntries = new HashMap<>();
        modifiedEntries.put(entryPath, editor.getText().toString().getBytes(StandardCharsets.UTF_8));

        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.repack_progress)
                .setMessage("Starting...")
                .setCancelable(false)
                .show();

        ApkRepacker.repack(this, apkFile, modifiedEntries,
                new ApkRepacker.ProgressListener() {
                    @Override
                    public void onProgress(String message) {
                        runOnUiThread(() -> {
                            if (progress.isShowing()) progress.setMessage(message);
                        });
                    }

                    @Override
                    public void onSuccess(File repackedApk) {
                        runOnUiThread(() -> {
                            progress.dismiss();
                            // Delete the cached edit file — APK is now the source of truth
                            if (workingFile != null) workingFile.delete();
                            Toast.makeText(TextEditorActivity.this,
                                    R.string.repack_success, Toast.LENGTH_SHORT).show();
                            wasModified = false;
                            dirty = false;
                            finish();
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
}
