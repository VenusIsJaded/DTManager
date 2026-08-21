package com.dt.manager.adapter

import android.content.Context
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dt.manager.R
import com.dt.manager.core.DexParser

/**
 * Adapter that renders a flat list of tree nodes (with depth/indent) for
 * the DEX Explorer view. Tapping a package expands/collapses its children.
 */
class DexNodeAdapter(
    private val ctx: Context,
    private val listener: OnNodeClickListener?
) : RecyclerView.Adapter<DexNodeAdapter.VH>() {

    fun interface OnNodeClickListener {
        fun onNodeClicked(node: DexParser.Node)
    }

    private val inflater: LayoutInflater = LayoutInflater.from(ctx)
    private val visible: MutableList<DexParser.Node> = ArrayList()

    fun setRoot(root: DexParser.Node?) {
        visible.clear()
        if (root != null) {
            for (c in root.children) {
                addToVisible(c, true)
            }
        }
        notifyDataSetChanged()
    }

    private fun addToVisible(n: DexParser.Node, expanded: Boolean) {
        visible.add(n)
        if (expanded && n.isPackage && n.hasChildren()) {
            for (c in n.children) {
                addToVisible(c, true)
            }
        }
    }

    fun toggle(node: DexParser.Node) {
        if (!node.isPackage) return
        val idx = visible.indexOf(node)
        if (idx < 0) return
        val wasExpanded = isExpanded(node, idx)
        if (wasExpanded) {
            collapseAt(idx + 1, node.depth)
        } else {
            expandAt(idx + 1, node)
        }
        notifyDataSetChanged()
    }

    private fun isExpanded(node: DexParser.Node, idx: Int): Boolean {
        if (idx + 1 >= visible.size) return false
        val next = visible[idx + 1]
        return next.depth > node.depth
    }

    private fun collapseAt(fromIdx: Int, parentDepth: Int) {
        var idx = fromIdx
        while (idx < visible.size && visible[idx].depth > parentDepth) {
            visible.removeAt(idx)
        }
    }

    private fun expandAt(fromIdx: Int, parent: DexParser.Node) {
        var insertAt = fromIdx
        for (c in parent.children) {
            visible.add(insertAt++, c)
        }
    }

    override fun getItemCount(): Int = visible.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = inflater.inflate(R.layout.item_dex_node, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.bind(visible[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val expander: ImageView = v.findViewById(R.id.expander)
        private val iconBg: ImageView = v.findViewById(R.id.iconBg)
        private val iconText: TextView = v.findViewById(R.id.iconText)
        private val title: TextView = v.findViewById(R.id.title)
        private val indent: View = v.findViewById(R.id.indent)

        fun bind(node: DexParser.Node) {
            val lp = indent.layoutParams
            lp.width = 24 * node.depth
            indent.layoutParams = lp

            title.text = node.name
            if (node.isPackage) {
                iconBg.visibility = View.VISIBLE
                iconBg.setBackgroundResource(R.drawable.bg_icon_default)
                iconText.text = ""
                iconText.visibility = View.GONE
                iconBg.setImageResource(R.drawable.ic_folder)
                iconBg.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), PorterDuff.Mode.SRC_ATOP)

                expander.visibility = View.VISIBLE
                expander.setImageResource(R.drawable.ic_arrow_back)
                val isExp = node.hasChildren() && isExpanded(node, bindingAdapterPosition)
                expander.rotation = if (isExp) -90f else 90f
            } else {
                iconBg.visibility = View.VISIBLE
                iconBg.setBackgroundResource(R.drawable.bg_icon_default)
                iconBg.setImageDrawable(null)
                iconText.visibility = View.VISIBLE
                iconText.text = "C"
                iconBg.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_blue), PorterDuff.Mode.SRC_ATOP)
                iconText.setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue))
                expander.visibility = View.INVISIBLE
            }

            itemView.setOnClickListener {
                if (node.isPackage) {
                    toggle(node)
                } else {
                    listener?.onNodeClicked(node)
                }
            }
        }
    }
}
