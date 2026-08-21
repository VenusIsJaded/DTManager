package com.dt.manager.ui;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
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
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DexViewerActivity extends AppCompatActivity {

    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_DEX_ENTRY = "dex_entry";
    public static final String EXTRA_DEX_FILE = "dex_file";

    private MaterialToolbar toolbar;
    private TabLayout tabs;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private ProgressBar loading;

    private File apkFile;
    private File dexFile;
    private String dexEntryPath;
    private ApkInspector inspector;

    private DexNodeAdapter adapter;
    private DexParser parser;
    private DexParser.Node root;

    private int currentTab = 0; // 0=explorer, 1=history, 2=search, 3=strings

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dex_viewer);

        toolbar = findViewById(R.id.toolbar);
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
            // Class tapped — show class details
            showClassDetails(node);
        });
        recyclerView.setAdapter(adapter);

        loadDexFile();
    }

    private void loadDexFile() {
        String apkPath = getIntent().getStringExtra(EXTRA_APK_PATH);
        String entry = getIntent().getStringExtra(EXTRA_DEX_ENTRY);
        String dexPath = getIntent().getStringExtra(EXTRA_DEX_FILE);

        if (dexPath != null) {
            dexFile = new File(dexPath);
            toolbar.setSubtitle(dexFile.getName());
            startParse();
            return;
        }

        if (apkPath != null && entry != null) {
            apkFile = new File(apkPath);
            dexEntryPath = entry;
            toolbar.setSubtitle(entry);
            try {
                inspector = new ApkInspector(apkFile);
                File cached = com.dt.manager.util.FileUtils.copyToCache(this,
                        inspector.openStream(entry), entry.replace("/", "_"));
                dexFile = cached;
                startParse();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.error_open_dex) + ": " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            Toast.makeText(this, R.string.error_open_dex, Toast.LENGTH_SHORT).show();
            finish();
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
                parser = new DexParser(dexFile);
                root = parser.buildTree();
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

    /** Show class details dialog with fields and methods. */
    private void showClassDetails(DexParser.Node node) {
        if (parser == null) {
            Toast.makeText(this, "DEX not loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        DexParser.ClassDef cd = parser.findClassDefByName(node.path);
        if (cd == null) {
            Toast.makeText(this, "Class not found in DEX", Toast.LENGTH_SHORT).show();
            return;
        }
        DexParser.ClassData data = parser.parseClassData(cd);
        String superclass = parser.superclass(cd);
        String sourceFile = parser.sourceFile(cd);

        StringBuilder sb = new StringBuilder();
        if (!sourceFile.isEmpty()) sb.append("Source: ").append(sourceFile).append("\n");
        if (!superclass.isEmpty()) sb.append("Superclass: ").append(superclass).append("\n");
        sb.append("\n== Fields (").append(data.fields.size()).append(") ==\n");
        for (DexParser.FieldInfo f : data.fields) {
            sb.append(f.modifierPrefix()).append(" ").append(f.type).append(" ").append(f.name);
            sb.append(f.isStatic ? " [static]\n" : "\n");
        }
        sb.append("\n== Methods (").append(data.methods.size()).append(") ==\n");
        for (DexParser.MethodInfo m : data.methods) {
            sb.append(m.modifierPrefix()).append(" ").append(m.name).append(m.prototype);
            sb.append(m.isDirect ? " [direct]\n" : "\n");
        }

        new AlertDialog.Builder(this)
                .setTitle(node.name)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
            if (dexFile != null) startParse();
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
