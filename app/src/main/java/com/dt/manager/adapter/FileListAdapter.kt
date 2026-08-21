package com.dt.manager.adapter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dt.manager.R
import com.dt.manager.core.ApkInspector
import com.dt.manager.util.FileUtils
import java.io.File
import java.util.concurrent.Executors

class FileListAdapter(
    private val ctx: Context,
    private val listener: OnItemClickListener?
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    companion object {
        @JvmField
        val PARENT_MARKER = Any()
    }

    interface OnItemClickListener {
        fun onItemClicked(item: Any)
        fun onItemLongClicked(item: Any): Boolean
    }

    private val items: MutableList<Any> = ArrayList()
    private val inflater: LayoutInflater = LayoutInflater.from(ctx)
    private val iconExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setItems(newItems: List<Any>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = inflater.inflate(R.layout.item_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        when (val o = items[position]) {
            PARENT_MARKER -> bindParent(h)
            is File -> bindFile(h, o)
            is ApkInspector.EntryInfo -> bindEntry(h, o)
        }
    }

    private fun bindParent(h: VH) {
        h.title.setText(R.string.parent_dir)
        h.subtitle.text = ""
        h.iconBg.setBackgroundResource(R.drawable.bg_icon_default)
        h.icon.setImageResource(R.drawable.ic_folder)
        h.icon.tag = null
        h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), PorterDuff.Mode.SRC_ATOP)
        h.itemView.setOnClickListener {
            listener?.onItemClicked(PARENT_MARKER)
        }
        h.itemView.setOnLongClickListener(null)
    }

    private fun bindFile(h: VH, f: File) {
        h.title.text = f.name
        val date = FileUtils.formatDate(f.lastModified())

        if (f.isDirectory) {
            val folders = FileUtils.countFolders(f)
            val files = FileUtils.countFiles(f)
            h.subtitle.text = ctx.getString(R.string.format_summary, folders, files) + "   " + date
            h.iconBg.setBackgroundResource(R.drawable.bg_icon_default)
            h.icon.setImageResource(R.drawable.ic_folder)
            h.icon.tag = null
            h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), PorterDuff.Mode.SRC_ATOP)
        } else {
            h.subtitle.text = date + "   " + FileUtils.humanReadable(f.length())
            val name = f.name.lowercase()
            if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
                loadApkIcon(h, f)
            } else {
                applyIconForName(h, f.name)
            }
        }
        h.itemView.setOnClickListener {
            listener?.onItemClicked(f)
        }
        h.itemView.setOnLongClickListener {
            listener?.onItemLongClicked(f) ?: false
        }
    }

    private fun bindEntry(h: VH, e: ApkInspector.EntryInfo) {
        h.title.text = e.name
        if (e.isDirectory) {
            h.subtitle.text = ""
            h.iconBg.setBackgroundResource(R.drawable.bg_icon_default)
            h.icon.setImageResource(R.drawable.ic_folder)
            h.icon.tag = null
            h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), PorterDuff.Mode.SRC_ATOP)
        } else {
            h.subtitle.text = FileUtils.humanReadable(e.size)
            applyIconForName(h, e.name)
        }
        h.itemView.setOnClickListener {
            listener?.onItemClicked(e)
        }
        h.itemView.setOnLongClickListener {
            listener?.onItemLongClicked(e) ?: false
        }
    }

    private fun loadApkIcon(h: VH, apkFile: File) {
        h.iconBg.setBackgroundResource(R.drawable.bg_icon_default)
        h.icon.setImageResource(R.drawable.ic_apk)
        h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_green), PorterDuff.Mode.SRC_ATOP)
        val tag = apkFile.absolutePath + "#" + h.bindingAdapterPosition
        h.icon.tag = tag
        iconExecutor.execute {
            var drawable: Drawable? = null
            try {
                val pm = ctx.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                if (pkgInfo?.applicationInfo != null) {
                    val ai: ApplicationInfo = pkgInfo.applicationInfo
                    ai.sourceDir = apkFile.absolutePath
                    ai.publicSourceDir = apkFile.absolutePath
                    drawable = pm.getApplicationIcon(ai)
                }
            } catch (_: Exception) {}
            val icon = drawable
            mainHandler.post {
                if (tag == h.icon.tag && icon != null) {
                    h.icon.setImageDrawable(icon)
                    h.icon.colorFilter = null
                }
            }
        }
    }

    private fun applyIconForName(h: VH, name: String) {
        val ext = FileUtils.extensionOf(name)
        when (ext) {
            "apk" -> {
                h.icon.setImageResource(R.drawable.ic_apk)
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_green), PorterDuff.Mode.SRC_ATOP)
            }
            "dex" -> {
                h.icon.setImageResource(R.drawable.ic_dex)
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_green), PorterDuff.Mode.SRC_ATOP)
            }
            "xml" -> {
                h.icon.setImageResource(R.drawable.ic_xml)
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_blue), PorterDuff.Mode.SRC_ATOP)
            }
            "arsc" -> {
                h.icon.setImageResource(R.drawable.ic_file)
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_orange), PorterDuff.Mode.SRC_ATOP)
            }
            "so" -> {
                h.icon.setImageResource(R.drawable.ic_file)
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_purple), PorterDuff.Mode.SRC_ATOP)
            }
            "json", "txt", "properties", "ts", "smali", "md", "yml", "yaml", "ini", "cfg", "csv", "log", "html", "css", "java", "kt" -> {
                h.icon.setImageResource(R.drawable.ic_file)
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent_blue), PorterDuff.Mode.SRC_ATOP)
            }
            else -> {
                h.icon.setImageResource(R.drawable.ic_file)
                h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary), PorterDuff.Mode.SRC_ATOP)
            }
        }
        h.iconBg.setBackgroundResource(R.drawable.bg_icon_default)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iconBg: ImageView = v.findViewById(R.id.iconBg)
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }
}
