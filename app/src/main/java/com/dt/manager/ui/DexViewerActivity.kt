package com.dt.manager.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dt.manager.R
import com.dt.manager.adapter.DexNodeAdapter
import com.dt.manager.core.ApkInspector
import com.dt.manager.core.DexParser
import com.dt.manager.core.SmaliGenerator
import com.dt.manager.util.FileUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class DexViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_DEX_ENTRIES = "dex_entries"
        const val EXTRA_DEX_ENTRY = "dex_entry"
        const val EXTRA_DEX_FILE = "dex_file"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var dexSpinner: Spinner
    private lateinit var tabs: TabLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loading: ProgressBar

    private var apkPath: String? = null
    private var dexEntries: ArrayList<String>? = null
    private var currentDexIndex = 0

    private var inspector: ApkInspector? = null
    private var currentDexFile: File? = null

    private lateinit var adapter: DexNodeAdapter
    private var parser: DexParser? = null
    private var root: DexParser.Node? = null

    private var currentTab = 0
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dex_viewer)

        toolbar = findViewById(R.id.toolbar)
        dexSpinner = findViewById(R.id.dexSpinner)
        tabs = findViewById(R.id.tabs)
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        loading = findViewById(R.id.loading)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setTitle(R.string.title_dex_viewer)

        tabs.addTab(tabs.newTab().setText(R.string.tab_explorer))
        tabs.addTab(tabs.newTab().setText(R.string.tab_history))
        tabs.addTab(tabs.newTab().setText(R.string.tab_search))
        tabs.addTab(tabs.newTab().setText(R.string.tab_strings))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                renderCurrentTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = DexNodeAdapter(this) { node ->
            openClassInSmaliEditor(node)
        }
        recyclerView.adapter = adapter

        loadDex()
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun loadDex() {
        apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        dexEntries = intent.getSerializableExtra(EXTRA_DEX_ENTRIES) as? ArrayList<String>
        if (dexEntries.isNullOrEmpty()) {
            val single = intent.getStringExtra(EXTRA_DEX_ENTRY)
            if (single != null) {
                dexEntries = arrayListOf(single)
            }
        }

        val dexPath = intent.getStringExtra(EXTRA_DEX_FILE)
        if (dexPath != null) {
            dexEntries = arrayListOf(File(dexPath).name)
            currentDexFile = File(dexPath)
            dexSpinner.visibility = View.GONE
            toolbar.subtitle = File(dexPath).name
            startParse()
            return
        }

        if (apkPath == null || dexEntries.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_open_dex, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val entries = dexEntries!!
        if (entries.size <= 1) {
            dexSpinner.visibility = View.GONE
        } else {
            dexSpinner.visibility = View.VISIBLE
            val spinnerAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, entries)
            spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
            dexSpinner.adapter = spinnerAdapter
            dexSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != currentDexIndex) {
                        currentDexIndex = position
                        dexEntries = arrayListOf(entries[position])
                        loadDexFile(entries[position])
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        loadDexFile(entries[currentDexIndex])
    }

    private fun loadDexFile(entryPath: String) {
        toolbar.subtitle = entryPath
        try {
            if (inspector == null) {
                inspector = ApkInspector(File(apkPath!!))
            }
            val cached = FileUtils.copyToCache(
                this,
                inspector!!.openStream(entryPath),
                entryPath.replace("/", "_")
            )
            currentDexFile = cached
            startParse()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_dex) + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startParse() {
        loading.visibility = View.VISIBLE
        executor.execute {
            var err: String? = null
            var resultNode: DexParser.Node? = null
            try {
                parser = DexParser(currentDexFile!!)
                root = parser!!.buildTree()

                val entries = dexEntries
                if (entries != null && entries.size > 1) {
                    for (i in entries.indices) {
                        if (i == currentDexIndex) continue
                        try {
                            val extraDexFile = FileUtils.copyToCache(
                                this@DexViewerActivity,
                                inspector!!.openStream(entries[i]),
                                entries[i].replace("/", "_") + "_merge"
                            )
                            val extraParser = DexParser(extraDexFile)
                            val extraRoot = extraParser.buildTree()
                            mergeTrees(root!!, extraRoot)
                            extraParser.close()
                        } catch (_: Exception) {}
                    }
                    root!!.sortChildren()
                }
                resultNode = root
            } catch (e: Exception) {
                err = e.message
            }

            runOnUiThread {
                loading.visibility = View.GONE
                if (resultNode == null) {
                    Toast.makeText(this@DexViewerActivity, getString(R.string.error_open_dex) + ": " + err, Toast.LENGTH_LONG).show()
                    emptyView.setText(R.string.empty_dex)
                    emptyView.visibility = View.VISIBLE
                } else {
                    renderCurrentTab()
                }
            }
        }
    }

    private fun mergeTrees(a: DexParser.Node, b: DexParser.Node) {
        for (bChild in b.children) {
            val aChild = a.findChild(bChild.name)
            if (aChild != null) {
                if (aChild.isPackage && bChild.isPackage) {
                    mergeTrees(aChild, bChild)
                }
            } else {
                a.children.add(bChild)
            }
        }
    }

    private fun renderCurrentTab() {
        val r = root ?: return
        when (currentTab) {
            0 -> renderExplorer(r)
            1 -> renderHistory(r)
            2 -> renderSearch(r)
            3 -> renderStrings()
        }
    }

    private fun renderExplorer(r: DexParser.Node) {
        adapter.setRoot(r)
        emptyView.visibility = if (r.hasChildren()) View.GONE else View.VISIBLE
        emptyView.setText(R.string.empty_dex)
    }

    private fun renderHistory(r: DexParser.Node) {
        adapter.setRoot(null)
        val all = ArrayList<String>()
        collectClassNames(r, all)
        emptyView.text = "${getString(R.string.tab_history)}: ${all.size} classes"
        emptyView.visibility = View.VISIBLE
    }

    private fun collectClassNames(n: DexParser.Node, out: MutableList<String>) {
        if (!n.isPackage && n.name.isNotEmpty()) out.add(n.path)
        for (c in n.children) collectClassNames(c, out)
    }

    private fun renderSearch(r: DexParser.Node) {
        val et = EditText(this).apply {
            hint = "Class or package name"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_search)
            .setView(et)
            .setPositiveButton(android.R.string.search_go) { _, _ ->
                val q = et.text.toString().trim().lowercase()
                if (q.isNotEmpty()) {
                    val results = ArrayList<String>()
                    searchClasses(r, q, results)
                    val preview = results.take(50).joinToString("\n")
                    emptyView.text = "Found ${results.size} matches\n\n$preview"
                    emptyView.visibility = View.VISIBLE
                    adapter.setRoot(null)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun searchClasses(n: DexParser.Node, q: String, out: MutableList<String>) {
        if (!n.isPackage && n.name.isNotEmpty() && n.path.lowercase().contains(q)) {
            out.add(n.path)
        }
        for (c in n.children) searchClasses(c, q, out)
    }

    private fun renderStrings() {
        val p = parser ?: return
        val strings = p.extractStrings()
        val preview = strings.take(200).joinToString("\n")
        emptyView.text = "Strings: ${strings.size}\n\n$preview"
        emptyView.visibility = View.VISIBLE
        adapter.setRoot(null)
    }

    private fun openClassInSmaliEditor(node: DexParser.Node) {
        val p = parser ?: run {
            Toast.makeText(this, "DEX not loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val cd = p.findClassDefByName(node.path) ?: run {
            Toast.makeText(this, "Class not found in DEX", Toast.LENGTH_SHORT).show()
            return
        }
        val smali = SmaliGenerator.generate(p, cd)
        try {
            val outFile = File(cacheDir, "smali_${System.currentTimeMillis()}_${node.name}.smali")
            FileOutputStream(outFile).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { w ->
                    w.write(smali)
                }
            }
            val intent = Intent(this, TextEditorActivity::class.java).apply {
                putExtra(TextEditorActivity.EXTRA_FILE_PATH, outFile.absolutePath)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open smali: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dex, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_search -> {
                tabs.getTabAt(2)?.select()
                root?.let { renderSearch(it) }
                true
            }
            R.id.action_refresh -> {
                if (currentDexFile != null) startParse()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { parser?.close() } catch (_: Exception) {}
        try { inspector?.close() } catch (_: Exception) {}
    }
}
