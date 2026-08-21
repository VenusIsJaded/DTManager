package com.dt.manager.ui;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dt.manager.R;
import com.dt.manager.adapter.DexNodeAdapter;
import com.dt.manager.core.ApkInspector;
import com.dt.manager.core.DexParser;
import com.dt.manager.core.SmaliGenerator;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DexViewerActivity extends AppCompatActivity {

    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_DEX_ENTRIES = "dex_entries"; // ArrayList<String>
    public static final String EXTRA_DEX_ENTRY = "dex_entry";
    public static final String EXTRA_DEX_FILE = "dex_file";

    private MaterialToolbar toolbar;
    private Spinner dexSpinner;
    private TabLayout tabs;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private ProgressBar loading;

    private String apkPath;
    private ArrayList<String> dexEntries;
    private int currentDexIndex = 0;

    private ApkInspector inspector;
    private File currentDexFile;

    private DexNodeAdapter adapter;
    private DexParser parser;
    private DexParser.Node root;

    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dex_viewer);

        toolbar = findViewById(R.id.toolbar);
        dexSpinner = findViewById(R.id.dexSpinner);
        tabs = findViewById(R.id.tabs);
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        loading = findViewById(R.id.loading);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setTitle(getString(R.string.title_dex_viewer));

        tabs.addTab(tabs.newTab().setText(R.string.tab_explorer));
        tabs.addTab(tabs.newTab().setText(R.string.tab_history));
        tabs.addTab(tabs.newTab().setText(R.string.tab_search));
        tabs.addTab(tabs.newTab().setText(R.string.tab_strings));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                renderCurrentTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DexNodeAdapter(this, node -> {
            // Class tapped — open the smali editor
            openClassInSmaliEditor(node);
        });
        recyclerView.setAdapter(adapter);

        loadDex();
    }

    @SuppressWarnings("unchecked")
    private void loadDex() {
        apkPath = getIntent().getStringExtra(EXTRA_APK_PATH);
        dexEntries = (ArrayList<String>) getIntent().getSerializableExtra(EXTRA_DEX_ENTRIES);
        if (dexEntries == null || dexEntries.isEmpty()) {
            // Fallback to single-entry mode
            String single = getIntent().getStringExtra(EXTRA_DEX_ENTRY);
            if (single != null) {
                dexEntries = new ArrayList<>();
                dexEntries.add(single);
            }
        }

        String dexPath = getIntent().getStringExtra(EXTRA_DEX_FILE);
        if (dexPath != null) {
            dexEntries = new ArrayList<>();
            dexEntries.add(new File(dexPath).getName());
            currentDexFile = new File(dexPath);
            // hide spinner
            dexSpinner.setVisibility(View.GONE);
            toolbar.setSubtitle(new File(dexPath).getName());
            startParse();
            return;
        }

        if (apkPath == null || dexEntries == null || dexEntries.isEmpty()) {
            Toast.makeText(this, R.string.error_open_dex, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Set up the spinner with all dex entries.
        // If multiple dex files are selected (merged view), hide the spinner
        // since switching would lose the merge.
        if (dexEntries.size() <= 1) {
            dexSpinner.setVisibility(View.GONE);
        } else {
            dexSpinner.setVisibility(View.VISIBLE);
            ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                    this, R.layout.spinner_item_dark, dexEntries);
            spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
            dexSpinner.setAdapter(spinnerAdapter);
            dexSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position != currentDexIndex) {
                        currentDexIndex = position;
                        // Switching to a single dex — replace dexEntries with just this one
                        ArrayList<String> single = new ArrayList<>();
                        single.add(dexEntries.get(position));
                        dexEntries = single;
                        loadDexFile(dexEntries.get(0));
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        loadDexFile(dexEntries.get(currentDexIndex));
    }

    private void loadDexFile(String entryPath) {
        toolbar.setSubtitle(entryPath);
        try {
            if (inspector == null) {
                inspector = new ApkInspector(new File(apkPath));
            }
            File cached = com.dt.manager.util.FileUtils.copyToCache(this,
                    inspector.openStream(entryPath), entryPath.replace("/", "_"));
            currentDexFile = cached;
            startParse();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_open_dex) + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startParse() {
        loading.setVisibility(View.VISIBLE);
        new ParseTask().execute();
    }

    private class ParseTask extends AsyncTask<Void, Void, DexParser.Node> {
        private String error;

        @Override
        protected DexParser.Node doInBackground(Void... voids) {
            try {
                parser = new DexParser(currentDexFile);
                root = parser.buildTree();

                // If we have multiple selected dex entries, merge their trees.
                // The selected dex files are in dexEntries (passed via intent).
                // currentDexIndex points to the first one (already parsed above).
                // Parse the rest and merge.
                if (dexEntries != null && dexEntries.size() > 1) {
                    for (int i = 0; i < dexEntries.size(); i++) {
                        if (i == currentDexIndex) continue; // already parsed
                        try {
                            File extraDexFile = com.dt.manager.util.FileUtils.copyToCache(
                                    DexViewerActivity.this,
                                    inspector.openStream(dexEntries.get(i)),
                                    dexEntries.get(i).replace("/", "_") + "_merge");
                            DexParser extraParser = new DexParser(extraDexFile);
                            DexParser.Node extraRoot = extraParser.buildTree();
                            mergeTrees(root, extraRoot);
                            extraParser.close();
                        } catch (Exception e) {
                            // Skip this dex if it fails
                        }
                    }
                    root.sortChildren();
                }
                return root;
            } catch (Exception e) {
                error = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onPostExecute(DexParser.Node node) {
            loading.setVisibility(View.GONE);
            if (node == null) {
                Toast.makeText(DexViewerActivity.this,
                        getString(R.string.error_open_dex) + ": " + error, Toast.LENGTH_LONG).show();
                emptyView.setText(R.string.empty_dex);
                emptyView.setVisibility(View.VISIBLE);
                return;
            }
            renderCurrentTab();
        }
    }

    /** Merge tree b into tree a (recursive). */
    private void mergeTrees(DexParser.Node a, DexParser.Node b) {
        for (DexParser.Node bChild : b.children) {
            DexParser.Node aChild = a.findChild(bChild.name);
            if (aChild != null) {
                // Both trees have this package/class — merge recursively
                if (aChild.isPackage && bChild.isPackage) {
                    mergeTrees(aChild, bChild);
                }
                // If it's a class, we just keep the first one's version
            } else {
                // Add b's child to a
                a.children.add(bChild);
            }
        }
    }

    private void renderCurrentTab() {
        if (root == null) return;
        switch (currentTab) {
            case 0: renderExplorer(); break;
            case 1: renderHistory(); break;
            case 2: renderSearch(); break;
            case 3: renderStrings(); break;
        }
    }

    private void renderExplorer() {
        adapter.setRoot(root);
        emptyView.setVisibility(root.hasChildren() ? View.GONE : View.VISIBLE);
        emptyView.setText(R.string.empty_dex);
    }

    private void renderHistory() {
        adapter.setRoot(null);
        java.util.List<String> all = new ArrayList<>();
        collectClassNames(root, all);
        emptyView.setText(getString(R.string.tab_history) + ": " + all.size() + " classes");
        emptyView.setVisibility(View.VISIBLE);
    }

    private void collectClassNames(DexParser.Node n, List<String> out) {
        if (!n.isPackage && !n.name.isEmpty()) out.add(n.path);
        for (DexParser.Node c : n.children) collectClassNames(c, out);
    }

    private void renderSearch() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Class or package name");
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_search)
                .setView(et)
                .setPositiveButton(android.R.string.search_go, (d, w) -> {
                    String q = et.getText().toString().trim().toLowerCase();
                    if (q.isEmpty()) return;
                    java.util.List<String> results = new ArrayList<>();
                    searchClasses(root, q, results);
                    emptyView.setText("Found " + results.size() + " matches\n\n" + String.join("\n", results.subList(0, Math.min(results.size(), 50))));
                    emptyView.setVisibility(View.VISIBLE);
                    adapter.setRoot(null);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void searchClasses(DexParser.Node n, String q, List<String> out) {
        if (!n.isPackage && !n.name.isEmpty() && n.path.toLowerCase().contains(q)) {
            out.add(n.path);
        }
        for (DexParser.Node c : n.children) searchClasses(c, q, out);
    }

    private void renderStrings() {
        if (parser == null) return;
        java.util.List<String> strings = parser.extractStrings();
        emptyView.setText("Strings: " + strings.size() + "\n\n" + String.join("\n", strings.subList(0, Math.min(strings.size(), 200))));
        emptyView.setVisibility(View.VISIBLE);
        adapter.setRoot(null);
    }

    /** Open the class in a smali editor instead of showing a popup. */
    private void openClassInSmaliEditor(DexParser.Node node) {
        if (parser == null) {
            Toast.makeText(this, "DEX not loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        DexParser.ClassDef cd = parser.findClassDefByName(node.path);
        if (cd == null) {
            Toast.makeText(this, "Class not found in DEX", Toast.LENGTH_SHORT).show();
            return;
        }
        String smali = SmaliGenerator.generate(parser, cd);
        // Write to a cache file and open the editor
        try {
            File outFile = new File(getCacheDir(),
                    "smali_" + System.currentTimeMillis() + "_" + node.name + ".smali");
            try (FileOutputStream fos = new FileOutputStream(outFile);
                 Writer w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(smali);
            }
            Intent intent = new Intent(this, TextEditorActivity.class);
            intent.putExtra(TextEditorActivity.EXTRA_FILE_PATH, outFile.getAbsolutePath());
            startActivity(intent);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to open smali: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_dex, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_search) {
            tabs.getTabAt(2).select();
            renderSearch();
            return true;
        } else if (id == R.id.action_refresh) {
            if (currentDexFile != null) startParse();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (parser != null) {
            try { parser.close(); } catch (Exception ignored) {}
        }
        if (inspector != null) {
            try { inspector.close(); } catch (Exception ignored) {}
        }
    }
}
