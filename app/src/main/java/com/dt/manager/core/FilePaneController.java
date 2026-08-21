package com.dt.manager.core;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dt.manager.MainActivity;
import com.dt.manager.R;
import com.dt.manager.adapter.FileListAdapter;
import com.dt.manager.util.FileUtils;

import java.io.File;
import java.util.List;
import java.util.Stack;

/**
 * One half of the dual-pane file browser. Holds its own current directory,
 * navigation history, RecyclerView, adapter, and path/summary TextViews.
 */
public class FilePaneController {

    public interface OnPaneNavigateListener {
        void onPaneActivated(FilePaneController pane);
    }

    private final View root;
    private final TextView pathText;
    private final TextView summaryText;
    private final RecyclerView recyclerView;
    private final TextView emptyView;
    private final FileListAdapter adapter;
    private final OnPaneNavigateListener listener;

    private File currentDir;
    private final Stack<File> history = new Stack<>();
    private String query = ""; // search filter

    public FilePaneController(Context ctx, View root,
                             FileListAdapter.OnItemClickListener itemListener,
                             OnPaneNavigateListener paneListener) {
        this.root = root;
        this.listener = paneListener;
        this.pathText = root.findViewById(R.id.panePath);
        this.summaryText = root.findViewById(R.id.paneSummary);
        this.recyclerView = root.findViewById(R.id.paneRecyclerView);
        this.emptyView = root.findViewById(R.id.paneEmpty);

        this.adapter = new FileListAdapter(ctx, itemListener);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(ctx));
        this.recyclerView.setAdapter(adapter);

        // Activate pane on touch
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

    public void setQuery(String q) {
        this.query = q == null ? "" : q.toLowerCase();
        refresh();
    }

    public void refresh() {
        if (currentDir == null) return;
        List<File> items = FileUtils.listFiles(currentDir);
        if (!query.isEmpty()) {
            List<File> filtered = new java.util.ArrayList<>();
            for (File f : items) {
                if (f.getName().toLowerCase().contains(query)) filtered.add(f);
            }
            items = filtered;
        }
        adapter.setItems(items);
        pathText.setText(currentDir.getAbsolutePath() + "/");
        int folders = FileUtils.countFolders(currentDir);
        int files = FileUtils.countFiles(currentDir);
        String disk = FileUtils.diskSummary(currentDir);
        if (disk.isEmpty()) {
            summaryText.setText("Folders: " + folders + "  Files: " + files);
        } else {
            String[] parts = disk.split("/");
            if (parts.length == 2) {
                summaryText.setText("Folders: " + folders + "  Files: " + files + "  Disk: " + parts[0] + "/" + parts[1]);
            } else {
                summaryText.setText("Folders: " + folders + "  Files: " + files);
            }
        }
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (!query.isEmpty()) {
            emptyView.setText("No matches for \"" + query + "\"");
        } else {
            emptyView.setText(R.string.empty_dir);
        }
    }

    public void updateHeader(String path, String summary) {
        pathText.setText(path);
        summaryText.setText(summary);
    }
}
