package com.dt.manager.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dt.manager.R
import com.dt.manager.core.ApkInspector
import com.dt.manager.core.ApkRepacker
import com.dt.manager.core.BinaryXmlDecoder
import com.dt.manager.core.BinaryXmlPatcher
import com.dt.manager.core.SyntaxHighlighter
import com.dt.manager.core.TextXmlHandler
import com.dt.manager.widget.CodeEditorView
import com.google.android.material.appbar.MaterialToolbar
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * High-performance text and XML editor supporting both compiled Binary XML (AXML)
 * and Plain Text XML files.
 */
class TextEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_ENTRY_PATH = "entry_path"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var editor: CodeEditorView
    private lateinit var status: TextView

    private var workingFile: File? = null
    private var dirty = false
    private var wasModified = false
    private var fromApk = false
    private var wasBinaryXml = false
    private var wasTextXml = false
    private var originalBinaryXml: ByteArray? = null
    private var originalDecodedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_editor)

        toolbar = findViewById(R.id.toolbar)
        editor = findViewById(R.id.editor)
        status = findViewById(R.id.status)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH)

        when {
            filePath != null -> {
                workingFile = File(filePath)
                toolbar.title = workingFile?.name
                loadFromDisk()
            }
            apkPath != null && entryPath != null -> {
                toolbar.title = File(entryPath).name
                loadFromApk(apkPath, entryPath)
            }
            else -> {
                Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dirty = true
                wasModified = true
                updateStatus()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadFromDisk() {
        val f = workingFile ?: return
        try {
            FileInputStream(f).use { inStream ->
                val raw = readAllBytes(inStream)
                val text = decodeBytes(raw)
                editor.language = SyntaxHighlighter.detectLanguage(f.name)
                editor.setText(text)
                dirty = false
                wasModified = false
                updateStatus()
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to load: " + e.message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadFromApk(apkPath: String, entryPath: String) {
        fromApk = true
        val key = Integer.toHexString((apkPath + "#" + entryPath).hashCode())
        val cacheDir = File(cacheDir, "apk_edits").apply { mkdirs() }
        val staged = File(cacheDir, key + "_" + File(entryPath).name)
        val binaryCache = File(cacheDir, key + "_" + File(entryPath).name + ".bin")

        if (!staged.exists()) {
            try {
                ApkInspector(File(apkPath)).use { inspector ->
                    val inStream = inspector.openStream(entryPath)
                    val raw = readAllBytes(inStream)
                    inStream.close()
                    FileOutputStream(binaryCache).use { binOut ->
                        binOut.write(raw)
                    }
                    val text = decodeBytes(raw)
                    FileOutputStream(staged).use { out ->
                        out.write(text.toByteArray(StandardCharsets.UTF_8))
                    }
                }
            } catch (e: IOException) {
                Toast.makeText(this, "Failed to open entry: " + e.message, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } else if (binaryCache.exists()) {
            try {
                FileInputStream(binaryCache).use { binIn ->
                    val raw = readAllBytes(binIn)
                    decodeBytes(raw)
                }
            } catch (_: IOException) {}
        }

        workingFile = staged
        try {
            FileInputStream(staged).use { inStream ->
                val raw = readAllBytes(inStream)
                val text = String(raw, StandardCharsets.UTF_8)
                editor.language = SyntaxHighlighter.detectLanguage(File(entryPath).name)
                editor.setText(text)
                dirty = false
                wasModified = false
                updateStatus()
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to load cached copy: " + e.message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun decodeBytes(raw: ByteArray): String {
        if (BinaryXmlDecoder.isBinaryXml(raw)) {
            wasBinaryXml = true
            wasTextXml = false
            originalBinaryXml = raw
            val decoded = BinaryXmlDecoder.decode(raw)
            originalDecodedText = decoded
            return decoded
        }
        if (TextXmlHandler.isTextXml(raw)) {
            wasTextXml = true
            wasBinaryXml = false
        }
        if (raw.size >= 3 && (raw[0].toInt() and 0xFF) == 0xEF && (raw[1].toInt() and 0xFF) == 0xBB && (raw[2].toInt() and 0xFF) == 0xBF) {
            return String(raw, 3, raw.size - 3, StandardCharsets.UTF_8)
        }
        return String(raw, StandardCharsets.UTF_8)
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var n: Int
        while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
        return out.toByteArray()
    }

    private fun save() {
        val f = workingFile ?: run {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            FileOutputStream(f).use { out ->
                out.write(editor.text.toString().toByteArray(StandardCharsets.UTF_8))
                dirty = false
                updateStatus()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Save failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        when {
            wasBinaryXml -> sb.append("Decoded from binary XML")
            wasTextXml -> sb.append("Plain text XML")
            fromApk -> sb.append("Cached copy")
        }
        when {
            dirty -> {
                if (sb.isNotEmpty()) sb.append(" — ")
                sb.append("Unsaved changes")
            }
            sb.isEmpty() -> sb.append("Saved")
            else -> sb.append(" — Saved")
        }
        status.text = sb.toString()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_save -> {
                save()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Handles the back / up-navigation flow.
     *
     * Three cases:
     *
     * 1. There are unsaved changes (`dirty`):
     *    Show a "Save changes?" dialog. The user can:
     *      - Save   → write to disk, then continue to case 2 (APK auto-sign prompt)
     *                or just finish (external file)
     *      - Discard → drop all edits. For APK entries this also deletes the
     *                staged cache file so the next open is fresh. Then finish.
     *      - Cancel → do nothing; stay in the editor.
     *
     * 2. No unsaved changes, but the user has modified an APK entry at some point
     *    (`wasModified && fromApk`): ask whether to repack + re-sign the APK.
     *
     * 3. Otherwise: just leave.
     */
    override fun onBackPressed() {
        when {
            dirty -> promptSaveOnLeave()
            wasModified && fromApk -> promptAutoSign()
            else -> super.onBackPressed()
        }
    }

    private fun promptSaveOnLeave() {
        AlertDialog.Builder(this)
            .setTitle(R.string.save_prompt_title)
            .setMessage(R.string.save_prompt_message)
            .setPositiveButton(R.string.save_prompt_save) { _, _ ->
                save()
                if (wasModified && fromApk) {
                    promptAutoSign()
                } else {
                    super.onBackPressed()
                }
            }
            .setNegativeButton(R.string.save_prompt_discard) { _, _ ->
                discardAndLeave()
            }
            .setNeutralButton(R.string.save_prompt_cancel, null)
            .show()
    }

    /**
     * Drop unsaved edits and leave the editor.
     *
     * For APK entries, this also deletes the staged cache file + binary XML
     * cache so that the next open starts fresh from the APK. Without this,
     * the user would see their discarded edits again next time.
     */
    private fun discardAndLeave() {
        dirty = false
        wasModified = false
        if (fromApk) {
            workingFile?.delete()
            val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
            val entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH)
            if (apkPath != null && entryPath != null) {
                val key = Integer.toHexString((apkPath + "#" + entryPath).hashCode())
                val cacheDir = File(cacheDir, "apk_edits")
                File(cacheDir, key + "_" + File(entryPath).name + ".bin").delete()
            }
            finish()
        } else {
            super.onBackPressed()
        }
    }

    private fun promptAutoSign() {
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_sign_title)
            .setMessage(R.string.auto_sign_message)
            .setPositiveButton(R.string.auto_sign_yes) { _, _ -> startRepack() }
            .setNegativeButton(R.string.auto_sign_no) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun startRepack() {
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: run { finish(); return }
        val entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH) ?: run { finish(); return }
        doRepack(apkPath, entryPath)
    }

    private fun doRepack(apkPath: String, entryPath: String) {
        val apkFile = File(apkPath)
        val modifiedEntries = HashMap<String, ByteArray>()

        val entryBytes: ByteArray
        val origBin = originalBinaryXml
        val origDec = originalDecodedText
        if (wasBinaryXml && origBin != null && origDec != null) {
            val editedText = editor.text.toString()
            val patched = BinaryXmlPatcher.patch(origBin, origDec, editedText)
            if (patched == null) {
                Toast.makeText(
                    this,
                    "Cannot patch binary XML (structure may have changed).",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            entryBytes = patched
        } else {
            entryBytes = editor.text.toString().toByteArray(StandardCharsets.UTF_8)
        }
        modifiedEntries[entryPath] = entryBytes

        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.repack_progress)
            .setMessage("Starting...")
            .setCancelable(false)
            .show()

        ApkRepacker.repack(
            this, apkFile, modifiedEntries,
            object : ApkRepacker.ProgressListener {
                override fun onProgress(message: String) {
                    runOnUiThread {
                        if (progress.isShowing) progress.setMessage(message)
                    }
                }

                override fun onSuccess(repackedApk: File) {
                    runOnUiThread {
                        progress.dismiss()
                        workingFile?.delete()
                        val key = Integer.toHexString((apkPath + "#" + entryPath).hashCode())
                        val cacheDir = File(cacheDir, "apk_edits")
                        val binaryCache = File(cacheDir, key + "_" + File(entryPath).name + ".bin")
                        if (binaryCache.exists()) binaryCache.delete()
                        Toast.makeText(this@TextEditorActivity, R.string.repack_success, Toast.LENGTH_SHORT).show()
                        wasModified = false
                        dirty = false
                        finish()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(
                            this@TextEditorActivity,
                            getString(R.string.repack_fail, message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }
}
