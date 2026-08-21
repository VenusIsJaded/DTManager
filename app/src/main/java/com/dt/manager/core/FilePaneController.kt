package com.dt.manager.core

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dt.manager.R
import com.dt.manager.adapter.FileListAdapter
import com.dt.manager.util.FileUtils
import java.io.File
import java.util.Stack

/**
 * One half of the dual-pane file browser. Holds its own current directory,
 * navigation history, RecyclerView, adapter. Each pane is fully independent
 * of the other.
 */
class FilePaneController(
    ctx: Context,
    val root: View,
    itemListener: FileListAdapter.OnItemClickListener,
    private val listener: OnPaneNavigateListener?
) {

    interface OnPaneNavigateListener {
        fun onPaneActivated(pane: FilePaneController)
        fun onPaneContentChanged(pane: FilePaneController)
    }

    private val emptyView: TextView = root.findViewById(R.id.paneEmpty)
    private val recyclerView: RecyclerView = root.findViewById(R.id.paneRecyclerView)
    private val adapter: FileListAdapter = FileListAdapter(ctx, itemListener)

    var currentDir: File? = null
        private set
    private val history = Stack<File>()
    private var query = ""

    init {
        recyclerView.layoutManager = LinearLayoutManager(ctx)
        recyclerView.adapter = adapter

        val touchListener = View.OnTouchListener { _, _ ->
            listener?.onPaneActivated(this)
            false
        }
        recyclerView.setOnTouchListener(touchListener)
        emptyView.setOnTouchListener(touchListener)
    }

    fun setRoot(dir: File?) {
        this.currentDir = dir
        history.clear()
        refresh()
    }

    fun navigateTo(dir: File) {
        if (currentDir != null) history.push(currentDir)
        currentDir = dir
        query = ""
        refresh()
    }

    fun goBack(): Boolean {
        if (history.isEmpty()) return false
        currentDir = history.pop()
        query = ""
        refresh()
        return true
    }

    fun canGoBack(): Boolean = history.isNotEmpty()

    fun setQuery(q: String?) {
        this.query = q?.lowercase() ?: ""
        refresh()
    }

    fun refresh() {
        val dir = currentDir ?: return
        var items = FileUtils.listFiles(dir)
        if (query.isNotEmpty()) {
            items = items.filter { it.name.lowercase().contains(query) }
        }

        val displayItems = ArrayList<Any>()
        if (canGoBack()) {
            displayItems.add(FileListAdapter.PARENT_MARKER)
        }
        displayItems.addAll(items)

        adapter.setItems(displayItems)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (query.isNotEmpty()) {
            emptyView.text = "No matches for \"$query\""
        } else {
            emptyView.setText(R.string.empty_dir)
        }

        listener?.onPaneContentChanged(this)
    }
}
