package com.dt.manager.core;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dt.manager.R;
import com.dt.manager.adapter.FileListAdapter;
import com.dt.manager.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * One half of the dual-pane file browser. Holds its own current directory,
 * navigation history, RecyclerView, adapter. Each pane is fully independent
 * of the other.
 *
 * A ".." entry is prepended to the listing whenever the pane has a parent
 * it can navigate back to. Tapping it pops the history.
 */
public class FilePaneController {

    public interface OnPaneNavigateListener {
        void onPaneActivated(FilePaneController pane);
        void onPaneContentChanged(FilePaneController pane);
    }

    private final View root;
    private final TextView emptyView;
    private final RecyclerView recyclerView;
    private final FileListAdapter adapter;
    private final OnPaneNavigateListener listener;

    private File currentDir;
    private final Stack<File> history = new Stack<>();
    private String query = "";

    public FilePaneController(Context ctx, View root,
                             FileListAdapter.OnItemClickListener itemListener,
                             OnPaneNavigateListener paneListener) {
        this.root = root;
        this.listener = paneListener;
        this.emptyView = root.findViewById(R.id.paneEmpty);
        this.recyclerView = root.findViewById(R.id.paneRecyclerView);

        this.adapter = new FileListAdapter(ctx, itemListener);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(ctx));
        this.recyclerView.setAdapter(adapter);

        // Activate pane on touch — pass-through to RecyclerView's item handlers
        View.OnTouchListener t = (v, e) -> {
            if (listener != null) listener.onPaneActivated(this);
            return false;
        };
        recyclerView.setOnTouchListener(t);
        emptyView.setOnTouchListener(t);
    }

    public View getRoot() { return root; }
    public File getCurrentDir() { return currentDir; }

    public void setRoot(File dir) {
        this.currentDir = dir;
        history.clear();
        refresh();
    }

    public void navigateTo(File dir) {
        if (currentDir != null) history.push(currentDir);
        currentDir = dir;
        query = "";
        refresh();
    }

    public boolean goBack() {
        if (history.isEmpty()) return false;
        currentDir = history.pop();
        query = "";
        refresh();
        return true;
    }

    public boolean canGoBack() { return !history.isEmpty(); }

    public void setQuery(String q) {
        this.query = q == null ? "" : q.toLowerCase();
        refresh();
    }

    public void refresh() {
        if (currentDir == null) return;
        List<File> items = FileUtils.listFiles(currentDir);
        if (!query.isEmpty()) {
            List<File> filtered = new ArrayList<>();
            for (File f : items) {
                if (f.getName().toLowerCase().contains(query)) filtered.add(f);
            }
            items = filtered;
        }

        // Build display list: prepend ".." if we have a parent to navigate to
        List<Object> displayItems = new ArrayList<>();
        if (canGoBack()) {
            displayItems.add(FileListAdapter.PARENT_MARKER);
        }
        displayItems.addAll(items);

        adapter.setItems(displayItems);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (!query.isEmpty()) {
            emptyView.setText("No matches for \"" + query + "\"");
        } else {
            emptyView.setText(R.string.empty_dir);
        }

        if (listener != null) listener.onPaneContentChanged(this);
    }
}
