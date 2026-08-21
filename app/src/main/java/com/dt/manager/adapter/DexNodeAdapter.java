package com.dt.manager.adapter;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.dt.manager.R;
import com.dt.manager.core.DexParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter that renders a flat list of tree nodes (with depth/indent) for
 * the DEX Explorer view. Tapping a package expands/collapses its children.
 */
public class DexNodeAdapter extends RecyclerView.Adapter<DexNodeAdapter.VH> {

    public interface OnNodeClickListener {
        void onNodeClicked(DexParser.Node node);
    }

    private final Context ctx;
    private final OnNodeClickListener listener;
    private final LayoutInflater inflater;
    private final List<DexParser.Node> visible = new ArrayList<>();

    public DexNodeAdapter(Context ctx, OnNodeClickListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setRoot(DexParser.Node root) {
        visible.clear();
        if (root != null) {
            for (DexParser.Node c : root.children) {
                addToVisible(c, true);
            }
        }
        notifyDataSetChanged();
    }

    private void addToVisible(DexParser.Node n, boolean expanded) {
        visible.add(n);
        if (expanded && n.isPackage && n.hasChildren()) {
            for (DexParser.Node c : n.children) {
                addToVisible(c, true);
            }
        }
    }

    /** Toggle expansion state of a package node */
    public void toggle(DexParser.Node node) {
        if (!node.isPackage) return;  // Only packages can be toggled
        int idx = visible.indexOf(node);
        if (idx < 0) return;
        boolean wasExpanded = isExpanded(node, idx);
        if (wasExpanded) {
            collapseAt(idx + 1, node.depth);
        } else {
            expandAt(idx + 1, node);
        }
        notifyDataSetChanged();
    }

    private boolean isExpanded(DexParser.Node node, int idx) {
        if (idx + 1 >= visible.size()) return false;
        DexParser.Node next = visible.get(idx + 1);
        return next.depth > node.depth;
    }

    private void collapseAt(int fromIdx, int parentDepth) {
        while (fromIdx < visible.size() && visible.get(fromIdx).depth > parentDepth) {
            visible.remove(fromIdx);
        }
    }

    private void expandAt(int fromIdx, DexParser.Node parent) {
        int insertAt = fromIdx;
        for (DexParser.Node c : parent.children) {
            visible.add(insertAt++, c);
        }
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.item_dex_node, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DexParser.Node node = visible.get(position);
        h.bind(node);
    }

    class VH extends RecyclerView.ViewHolder {
        final ImageView expander;
        final ImageView iconBg;
        final TextView iconText;
        final TextView title;
        final View indent;

        VH(@NonNull View v) {
            super(v);
            expander = v.findViewById(R.id.expander);
            iconBg = v.findViewById(R.id.iconBg);
            iconText = v.findViewById(R.id.iconText);
            title = v.findViewById(R.id.title);
            indent = v.findViewById(R.id.indent);
        }

        void bind(DexParser.Node node) {
            ViewGroup.LayoutParams lp = indent.getLayoutParams();
            lp.width = 24 * node.depth;
            indent.setLayoutParams(lp);

            title.setText(node.name);
            if (node.isPackage) {
                iconBg.setVisibility(View.VISIBLE);
                iconBg.setBackgroundResource(R.drawable.bg_icon_default);
                iconText.setText("");
                iconText.setVisibility(View.GONE);
                // Folder image
                iconBg.setImageResource(R.drawable.ic_folder);
                iconBg.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), PorterDuff.Mode.SRC_ATOP);
                // expander
                expander.setVisibility(View.VISIBLE);
                expander.setImageResource(R.drawable.ic_arrow_back);
                expander.setRotation(node.hasChildren() && isExpanded(node, getBindingAdapterPosition()) ? -90f : 90f);
            } else {
                iconBg.setVisibility(View.VISIBLE);
                iconBg.setBackgroundResource(R.drawable.bg_icon_default);
                iconBg.setImageDrawable(null);
                iconText.setVisibility(View.VISIBLE);
                iconText.setText("C");
                iconBg.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_blue), PorterDuff.Mode.SRC_ATOP);
                iconText.setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue));
                expander.setVisibility(View.INVISIBLE);
            }

            itemView.setOnClickListener(v -> {
                if (node.isPackage) {
                    // Package: expand/collapse
                    toggle(node);
                } else {
                    // Class: open in smali editor (DON'T call toggle — it would
                    // call onNodeClicked again and open the editor twice!)
                    if (listener != null) listener.onNodeClicked(node);
                }
            });
        }
    }
}
