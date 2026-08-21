package com.dt.manager.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dt.manager.R
import com.dt.manager.adapter.FileListAdapter
import com.dt.manager.core.ApkInspector
import com.dt.manager.core.ApkInstaller
import com.dt.manager.util.FileUtils
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.util.Stack

class ApkViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "apk_path"

        private val TEXT_EXT = hashSetOf(
            "txt", "ts", "js", "json", "xml", "smali", "properties", "md",
            "yml", "yaml", "ini", "cfg", "csv", "log", "html", "css", "java", "kt"
        )
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pathText: TextView
    private lateinit var summaryText: TextView
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileListAdapter

    private lateinit var apkFile: File
    private var inspector: ApkInspector? = null
    private var currentDir = ""
    private val history = Stack<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apk_viewer)

        toolbar = findViewById(R.id.toolbar)
        pathText = findViewById(R.id.pathText)
        summaryText = findViewById(R.id.summaryText)
        emptyView = findViewById(R.id.emptyView)
        recyclerView = findViewById(R.id.recyclerView)

        val path = intent.getStringExtra(EXTRA_APK_PATH)
        if (path.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_open_apk, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        apkFile = File(path)
        try {
            inspector = ApkInspector(apkFile)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_apk) + ": " + e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        toolbar.title = "${apkFile.name}/"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = FileListAdapter(this, object : FileListAdapter.OnItemClickListener {
            override fun onItemClicked(item: Any) {
                handleEntryClick(item)
            }

            override fun onItemLongClicked(item: Any): Boolean {
                if (item is ApkInspector.EntryInfo) {
                    showEntryOptions(item)
                    return true
                }
                return false
            }
        })
        recyclerView.adapter = adapter

        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_apk, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_extract_all -> {
                Toast.makeText(this, R.string.error_not_implemented, Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refresh() {
        val insp = inspector ?: return
        val items = insp.listInDirectory(currentDir)
        val display = ArrayList<Any>()
        if (history.isNotEmpty()) display.add(FileListAdapter.PARENT_MARKER)
        display.addAll(items)
        adapter.setItems(display)

        val displayPath = if (currentDir.isEmpty()) "${apkFile.name}/" else "${apkFile.name}/$currentDir/"
        pathText.text = displayPath

        var folders = 0
        var files = 0
        for (e in items) {
            if (e.isDirectory) folders++ else files++
        }
        summaryText.text = getString(R.string.format_summary, folders, files)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleEntryClick(item: Any) {
        if (item === FileListAdapter.PARENT_MARKER) {
            onBackPressed()
            return
        }
        if (item !is ApkInspector.EntryInfo) return

        if (item.isDirectory) {
            history.push(currentDir)
            currentDir = item.path
            refresh()
            return
        }
        val name = item.name.lowercase()
        when {
            name.endsWith(".dex") -> showDexOptions(item)
            name.endsWith(".apk") -> {
                try {
                    val staged = FileUtils.copyToCache(this, inspector!!.openStream(item.path), item.name)
                    showNestedApkInfoDialog(staged)
                } catch (ex: Exception) {
                    Toast.makeText(this, ex.message, Toast.LENGTH_LONG).show()
                }
            }
            isTextEntry(name) -> openInTextEditor(item.path)
            else -> Toast.makeText(this, R.string.error_no_handler, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNestedApkInfoDialog(apkFile: File) {
        ApkInfoDialog.show(this, apkFile, object : ApkInfoDialog.Listener {
            override fun onView(apkFile: File) {
                val intent = Intent(this@ApkViewerActivity, ApkViewerActivity::class.java).apply {
                    putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
                }
                startActivity(intent)
            }

            override fun onInstall(apkFile: File) {
                ApkInstaller(this@ApkViewerActivity, object : ApkInstaller.Callback {
                    override fun onSuccess(message: String) {
                        Toast.makeText(this@ApkViewerActivity, message, Toast.LENGTH_SHORT).show()
                    }
                    override fun onError(message: String) {
                        Toast.makeText(this@ApkViewerActivity, getString(R.string.install_fail, message), Toast.LENGTH_LONG).show()
                    }
                }).install(apkFile)
            }

            override fun onFunctions(apkFile: File) {
                val msg = "Path: ${apkFile.absolutePath}\nSize: ${FileUtils.humanReadable(apkFile.length())}"
                AlertDialog.Builder(this@ApkViewerActivity)
                    .setTitle(apkFile.name)
                    .setMessage(msg)
                    .show()
            }
        })
    }

    private fun showDexOptions(e: ApkInspector.EntryInfo) {
        val allDexFiles = findAllDexFiles()
        if (allDexFiles.size <= 1) {
            openDexViewer(e.path, allDexFiles)
            return
        }

        var tappedIdx = allDexFiles.indexOf(e.path)
        if (tappedIdx < 0) tappedIdx = 0
        val checked = BooleanArray(allDexFiles.size)
        checked[tappedIdx] = true

        val items = allDexFiles.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("MultiDex")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("SELECT ALL") { d, _ ->
                d.dismiss()
                for (i in checked.indices) checked[i] = true
                showDexOptionsWithState(allDexFiles, checked)
            }
            .setNeutralButton("OK") { _, _ ->
                val selected = ArrayList<String>()
                for (i in checked.indices) {
                    if (checked[i]) selected.add(allDexFiles[i])
                }
                if (selected.isEmpty()) selected.add(allDexFiles[tappedIdx])
                openDexViewer(selected[0], selected)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showDexOptionsWithState(allDexFiles: List<String>, checked: BooleanArray) {
        val items = allDexFiles.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("MultiDex")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val selected = ArrayList<String>()
                for (i in checked.indices) {
                    if (checked[i]) selected.add(allDexFiles[i])
                }
                if (selected.isEmpty()) selected.add(allDexFiles[0])
                openDexViewer(selected[0], selected)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun findAllDexFiles(): List<String> {
        val insp = inspector ?: return emptyList()
        val out = ArrayList<String>()
        for (e in insp.listInDirectory("")) {
            if (!e.isDirectory && e.name.lowercase().endsWith(".dex")) {
                out.add(e.path)
            }
        }
        return out
    }

    private fun isTextEntry(lowerName: String): Boolean {
        val dot = lowerName.lastIndexOf('.')
        if (dot < 0) return false
        return TEXT_EXT.contains(lowerName.substring(dot + 1))
    }

    private fun openInTextEditor(entryPath: String) {
        val intent = Intent(this, TextEditorActivity::class.java).apply {
            putExtra(TextEditorActivity.EXTRA_APK_PATH, apkFile.absolutePath)
            putExtra(TextEditorActivity.EXTRA_ENTRY_PATH, entryPath)
        }
        startActivity(intent)
    }

    private fun openDexViewer(entryPath: String, allDexFiles: List<String>?) {
        val intent = Intent(this, DexViewerActivity::class.java).apply {
            putExtra(DexViewerActivity.EXTRA_APK_PATH, apkFile.absolutePath)
            putExtra(DexViewerActivity.EXTRA_DEX_ENTRY, entryPath)
            if (!allDexFiles.isNullOrEmpty()) {
                putExtra(DexViewerActivity.EXTRA_DEX_ENTRIES, ArrayList(allDexFiles))
            }
        }
        startActivity(intent)
    }

    private fun showEntryOptions(e: ApkInspector.EntryInfo) {
        if (e.isDirectory) {
            AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage("Folder")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = ArrayList<String>()
        val actions = ArrayList<Runnable>()

        val name = e.name.lowercase()
        if (name.endsWith(".dex")) {
            labels.add("Open in Dex Editor")
            actions.add(Runnable { showDexOptions(e) })
        } else if (name.endsWith(".apk")) {
            labels.add("Install")
            actions.add(Runnable {
                try {
                    val staged = FileUtils.copyToCache(this, inspector!!.openStream(e.path), e.name)
                    ApkInstaller(this, object : ApkInstaller.Callback {
                        override fun onSuccess(m: String) {
                            Toast.makeText(this@ApkViewerActivity, m, Toast.LENGTH_SHORT).show()
                        }
                        override fun onError(m: String) {
                            Toast.makeText(this@ApkViewerActivity, getString(R.string.install_fail, m), Toast.LENGTH_LONG).show()
                        }
                    }).install(staged)
                } catch (ex: Exception) {
                    Toast.makeText(this, ex.message, Toast.LENGTH_LONG).show()
                }
            })
        } else if (isTextEntry(name)) {
            labels.add("Edit")
            actions.add(Runnable { openInTextEditor(e.path) })
        }

        labels.add("Properties")
        actions.add(Runnable {
            val msg = "Path: ${e.path}\nSize: ${FileUtils.humanReadable(e.size)}\nModified: ${FileUtils.formatDate(e.time)}"
            AlertDialog.Builder(this).setTitle(e.name).setMessage(msg).show()
        })

        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(labels.toTypedArray<CharSequence>()) { _, which ->
                if (which in actions.indices) actions[which].run()
            }
            .show()
    }

    override fun onBackPressed() {
        if (history.isNotEmpty()) {
            currentDir = history.pop()
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            inspector?.close()
        } catch (_: Exception) {}
    }
}
