package com.dt.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dt.manager.adapter.FileListAdapter
import com.dt.manager.core.ApkInstaller
import com.dt.manager.core.FileClipboard
import com.dt.manager.core.FilePaneController
import com.dt.manager.ui.AboutActivity
import com.dt.manager.ui.ApkInfoDialog
import com.dt.manager.ui.ApkViewerActivity
import com.dt.manager.ui.TextEditorActivity
import com.dt.manager.util.FileUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private val TEXT_EXT = hashSetOf(
            "txt", "ts", "js", "json", "xml", "smali", "properties", "md",
            "yml", "yaml", "ini", "cfg", "csv", "log", "html", "css", "java", "kt"
        )
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pathText: TextView
    private lateinit var summaryText: TextView
    private lateinit var paneLeftWrap: FrameLayout
    private lateinit var paneRightWrap: FrameLayout
    private lateinit var fab: FloatingActionButton

    private lateinit var leftPane: FilePaneController
    private lateinit var rightPane: FilePaneController
    private var activePane: FilePaneController? = null

    private val storagePermLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                initPanes()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tryOpenAllFilesAccess()
            }
        }

    private val allFilesLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                initPanes()
            } else {
                Toast.makeText(this, R.string.grant_storage_perm, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        pathText = findViewById(R.id.pathText)
        summaryText = findViewById(R.id.summaryText)
        paneLeftWrap = findViewById(R.id.paneLeftWrap)
        paneRightWrap = findViewById(R.id.paneRightWrap)
        fab = findViewById(R.id.fab)

        setSupportActionBar(toolbar)

        val itemClickListener = object : FileListAdapter.OnItemClickListener {
            override fun onItemClicked(item: Any) {
                if (item === FileListAdapter.PARENT_MARKER) {
                    if (activePane?.goBack() == true) updateHeaderForActivePane()
                    return
                }
                if (item is File) handleFileClick(item)
            }

            override fun onItemLongClicked(item: Any): Boolean {
                if (item is File) {
                    showFileOptions(item)
                    return true
                }
                return false
            }
        }

        val navListener = object : FilePaneController.OnPaneNavigateListener {
            override fun onPaneActivated(pane: FilePaneController) {
                setActivePane(pane)
            }

            override fun onPaneContentChanged(pane: FilePaneController) {
                if (pane === activePane) updateHeaderForActivePane()
            }
        }

        val leftRoot = findViewById<View>(R.id.paneLeft)
        val rightRoot = findViewById<View>(R.id.paneRight)
        leftPane = FilePaneController(this, leftRoot, itemClickListener, navListener)
        rightPane = FilePaneController(this, rightRoot, itemClickListener, navListener)

        fab.setOnClickListener {
            activePane?.refresh()
        }

        if (!checkStoragePermission()) {
            requestStoragePermission()
        } else {
            initPanes()
        }
    }

    private fun initPanes() {
        val root = FileUtils.getRootStoragePath(this)
        leftPane.setRoot(root)
        rightPane.setRoot(root)
        setActivePane(leftPane)
    }

    private fun setActivePane(pane: FilePaneController) {
        activePane = pane
        paneLeftWrap.setBackgroundColor(
            ContextCompat.getColor(this, if (pane === leftPane) R.color.bg_tertiary else R.color.bg_primary)
        )
        paneRightWrap.setBackgroundColor(
            ContextCompat.getColor(this, if (pane === rightPane) R.color.bg_tertiary else R.color.bg_primary)
        )
        updateHeaderForActivePane()
    }

    private fun updateHeaderForActivePane() {
        val cur = activePane?.currentDir ?: return
        pathText.text = "${cur.absolutePath}/"
        val folders = FileUtils.countFolders(cur)
        val files = FileUtils.countFiles(cur)
        val disk = FileUtils.diskSummary(cur)
        if (disk.isEmpty()) {
            summaryText.text = getString(R.string.format_summary, folders, files)
        } else {
            val parts = disk.split('/')
            if (parts.size == 2) {
                summaryText.text = getString(R.string.format_summary_disk, folders, files, parts[0], parts[1])
            } else {
                summaryText.text = getString(R.string.format_summary, folders, files)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val pasteItem = menu.findItem(R.id.action_paste)
        if (pasteItem != null) {
            pasteItem.isVisible = !FileClipboard.getInstance().isEmpty
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                activePane?.refresh()
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            R.id.action_search -> {
                showSearchDialog()
                true
            }
            R.id.action_paste -> {
                pasteIntoActivePane()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSearchDialog() {
        val pane = activePane ?: return
        val et = EditText(this).apply {
            hint = "Filter by name (case-insensitive)"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_search)
            .setView(et)
            .setPositiveButton(android.R.string.search_go) { _, _ ->
                val q = et.text.toString().trim()
                pane.setQuery(q.lowercase())
                updateHeaderForActivePane()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tryOpenAllFilesAccess()
        } else {
            storagePermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun tryOpenAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle(R.string.grant_storage_perm)
                .setMessage(R.string.grant_storage_perm)
                .setPositiveButton(R.string.grant) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        allFilesLauncher.launch(intent)
                    } catch (_: Exception) {
                        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        allFilesLauncher.launch(fallback)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun handleFileClick(f: File) {
        if (activePane == null) setActivePane(leftPane)
        val currentActive = activePane ?: leftPane
        if (f.isDirectory) {
            currentActive.navigateTo(f)
            updateHeaderForActivePane()
        } else {
            val name = f.name.lowercase()
            when {
                name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm") -> {
                    showApkInfoDialog(f)
                }
                name.endsWith(".zip") -> {
                    val intent = Intent(this, ApkViewerActivity::class.java).apply {
                        putExtra(ApkViewerActivity.EXTRA_APK_PATH, f.absolutePath)
                    }
                    startActivity(intent)
                }
                isTextFile(name) -> openTextEditor(f)
                else -> Toast.makeText(this, R.string.error_no_handler, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isTextFile(lowerName: String): Boolean {
        val dot = lowerName.lastIndexOf('.')
        if (dot < 0) return false
        return TEXT_EXT.contains(lowerName.substring(dot + 1))
    }

    private fun openTextEditor(file: File) {
        val intent = Intent(this, TextEditorActivity::class.java).apply {
            putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        }
        startActivity(intent)
    }

    private fun showApkInfoDialog(apkFile: File) {
        ApkInfoDialog.show(this, apkFile, object : ApkInfoDialog.Listener {
            override fun onView(apkFile: File) {
                val intent = Intent(this@MainActivity, ApkViewerActivity::class.java).apply {
                    putExtra(ApkViewerActivity.EXTRA_APK_PATH, apkFile.absolutePath)
                }
                startActivity(intent)
            }

            override fun onInstall(apkFile: File) {
                ApkInstaller(this@MainActivity, object : ApkInstaller.Callback {
                    override fun onSuccess(message: String) {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                    override fun onError(message: String) {
                        Toast.makeText(this@MainActivity, getString(R.string.install_fail, message), Toast.LENGTH_LONG).show()
                    }
                }).install(apkFile)
            }

            override fun onFunctions(apkFile: File) {
                showFunctionsMenu(apkFile)
            }
        })
    }

    private fun showFunctionsMenu(apkFile: File) {
        AlertDialog.Builder(this)
            .setTitle(apkFile.name)
            .setItems(arrayOf("Install", "View inside", "Properties")) { _, which ->
                when (which) {
                    0 -> {
                        ApkInstaller(this, object : ApkInstaller.Callback {
                            override fun onSuccess(m: String) { Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show() }
                            override fun onError(m: String) { Toast.makeText(this@MainActivity, getString(R.string.install_fail, m), Toast.LENGTH_LONG).show() }
                        }).install(apkFile)
                    }
                    1 -> {
                        val intent = Intent(this, ApkViewerActivity::class.java).apply {
                            putExtra(ApkViewerActivity.EXTRA_APK_PATH, apkFile.absolutePath)
                        }
                        startActivity(intent)
                    }
                    2 -> showProperties(apkFile)
                }
            }
            .show()
    }

    private fun showFileOptions(f: File) {
        val labels = ArrayList<String>()
        val actions = ArrayList<Runnable>()

        labels.add(getString(R.string.action_copy))
        actions.add(Runnable {
            FileClipboard.getInstance().set(f, FileClipboard.Action.COPY)
            Toast.makeText(this, "Copied: " + f.name, Toast.LENGTH_SHORT).show()
            invalidateOptionsMenu()
        })
        labels.add(getString(R.string.action_cut))
        actions.add(Runnable {
            FileClipboard.getInstance().set(f, FileClipboard.Action.CUT)
            Toast.makeText(this, "Cut: " + f.name, Toast.LENGTH_SHORT).show()
            invalidateOptionsMenu()
        })

        if (f.isDirectory) {
            labels.add(getString(R.string.action_properties))
            actions.add(Runnable { showProperties(f) })
        } else {
            val name = f.name.lowercase()
            if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
                labels.add(getString(R.string.action_details))
                actions.add(Runnable { showApkInfoDialog(f) })
                labels.add(getString(R.string.action_install))
                actions.add(Runnable {
                    ApkInstaller(this, object : ApkInstaller.Callback {
                        override fun onSuccess(m: String) { Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show() }
                        override fun onError(m: String) { Toast.makeText(this@MainActivity, getString(R.string.install_fail, m), Toast.LENGTH_LONG).show() }
                    }).install(f)
                })
            } else if (isTextFile(name)) {
                labels.add("Edit")
                actions.add(Runnable { openTextEditor(f) })
            }
            labels.add(getString(R.string.action_properties))
            actions.add(Runnable { showProperties(f) })
        }

        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(labels.toTypedArray<CharSequence>()) { _, which ->
                if (which in actions.indices) actions[which].run()
            }
            .show()
    }

    private fun pasteIntoActivePane() {
        val clip = FileClipboard.getInstance()
        if (clip.isEmpty) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val destDir = activePane?.currentDir ?: return
        val src = clip.source ?: return

        if (src.isDirectory && destDir.absolutePath.startsWith(src.absolutePath)) {
            Toast.makeText(this, getString(R.string.paste_fail, "cannot paste folder into itself"), Toast.LENGTH_LONG).show()
            return
        }

        val isCut = clip.action == FileClipboard.Action.CUT

        Thread {
            try {
                val result = FileUtils.copy(src, destDir)
                if (isCut && src.absolutePath != result.absolutePath) {
                    FileUtils.deleteRecursive(src)
                }
                runOnUiThread {
                    if (isCut) clip.clear()
                    Toast.makeText(this, getString(R.string.paste_done, result.name), Toast.LENGTH_SHORT).show()
                    activePane?.refresh()
                    invalidateOptionsMenu()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.paste_fail, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showProperties(f: File) {
        val msg = "Path: ${f.absolutePath}\nSize: ${FileUtils.humanReadable(f.length())}\nModified: ${FileUtils.formatDate(f.lastModified())}\nReadable: ${f.canRead()}\nWritable: ${f.canWrite()}"
        AlertDialog.Builder(this).setTitle(f.name).setMessage(msg).show()
    }

    override fun onBackPressed() {
        if (activePane?.goBack() == true) {
            updateHeaderForActivePane()
            return
        }
        super.onBackPressed()
    }
}
