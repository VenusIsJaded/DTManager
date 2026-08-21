package com.dt.manager.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    const val DATE_FORMAT = "dd-MM-yy HH:mm"

    @JvmStatic
    fun formatDate(millis: Long): String {
        if (millis <= 0) return "—"
        return SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date(millis))
    }

    @JvmStatic
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "—"
        return Formatter.formatShortFileSize(null, bytes)
    }

    @JvmStatic
    fun humanReadable(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(1, 6)
        val prefix = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), prefix)
    }

    @JvmStatic
    fun listFiles(dir: File?): List<File> {
        if (dir == null || !dir.exists() || !dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()

        val folders = ArrayList<File>()
        val files = ArrayList<File>()
        for (f in children) {
            if (f.isHidden) continue
            if (f.isDirectory) folders.add(f)
            else files.add(f)
        }

        folders.sortBy { it.name.lowercase(Locale.ROOT) }
        files.sortBy { it.name.lowercase(Locale.ROOT) }

        return ArrayList<File>(folders.size + files.size).apply {
            addAll(folders)
            addAll(files)
        }
    }

    @JvmStatic
    fun countFolders(dir: File?): Int {
        val children = dir?.listFiles() ?: return 0
        var count = 0
        for (f in children) {
            if (!f.isHidden && f.isDirectory) count++
        }
        return count
    }

    @JvmStatic
    fun countFiles(dir: File?): Int {
        val children = dir?.listFiles() ?: return 0
        var count = 0
        for (f in children) {
            if (!f.isHidden && f.isFile) count++
        }
        return count
    }

    @JvmStatic
    fun diskSummary(path: File?): String {
        if (path == null) return ""
        return try {
            val stat = StatFs(path.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free
            "${humanReadable(used)}/${humanReadable(total)}"
        } catch (_: Exception) {
            ""
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyToCache(ctx: Context, input: InputStream, name: String): File {
        val out = File(ctx.cacheDir, name)
        out.parentFile?.mkdirs()
        input.use { inStream ->
            FileOutputStream(out).use { fos ->
                val buf = ByteArray(16384)
                var n: Int
                while (inStream.read(buf).also { n = it } > 0) {
                    fos.write(buf, 0, n)
                }
            }
        }
        return out
    }

    /**
     * Copy a file or whole directory tree from src to dest. If dest exists,
     * a numeric suffix is appended to the name (e.g. "foo (1).apk").
     */
    @JvmStatic
    @Throws(IOException::class)
    fun copy(src: File, destDir: File): File {
        val target = uniqueDestination(destDir, src.name)
        if (src.isDirectory) {
            copyDir(src, target)
        } else {
            copyFile(src, target)
        }
        return target
    }

    @Throws(IOException::class)
    private fun copyDir(src: File, dest: File) {
        if (!dest.exists()) dest.mkdirs()
        val children = src.listFiles() ?: return
        for (c in children) {
            val childDest = File(dest, c.name)
            if (c.isDirectory) copyDir(c, childDest)
            else copyFile(c, childDest)
        }
    }

    @Throws(IOException::class)
    private fun copyFile(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        FileInputStream(src).use { inStream ->
            FileOutputStream(dest).use { outStream ->
                val buf = ByteArray(16384)
                var n: Int
                while (inStream.read(buf).also { n = it } > 0) {
                    outStream.write(buf, 0, n)
                }
            }
        }
    }

    /** Recursively delete a file or directory. */
    @JvmStatic
    fun deleteRecursive(f: File?): Boolean {
        if (f == null || !f.exists()) return false
        if (f.isDirectory) {
            val children = f.listFiles()
            if (children != null) {
                for (c in children) deleteRecursive(c)
            }
        }
        return f.delete()
    }

    /** If target name already exists in destDir, append " (N)" before extension. */
    @JvmStatic
    fun uniqueDestination(destDir: File, name: String): File {
        var target = File(destDir, name)
        if (!target.exists()) return target
        var base = name
        var ext = ""
        val dot = name.lastIndexOf('.')
        if (dot in 1 until name.length - 1) {
            base = name.substring(0, dot)
            ext = name.substring(dot)
        }
        var i = 1
        while (true) {
            target = File(destDir, "$base ($i)$ext")
            if (!target.exists()) return target
            i++
        }
    }

    @JvmStatic
    fun getRootStoragePath(ctx: Context?): File {
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && ext.exists()) return ext
        val fallback = Environment.getDataDirectory()
        return fallback ?: File("/storage/emulated/0")
    }

    @JvmStatic
    fun mimeForName(name: String?): String {
        if (name == null) return "*/*"
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".apkm") ->
                "application/vnd.android.package-archive"
            lower.endsWith(".dex") || lower.endsWith(".arsc") || lower.endsWith(".so") ->
                "application/octet-stream"
            lower.endsWith(".xml") -> "text/xml"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".txt") -> "text/plain"
            else -> "*/*"
        }
    }

    @JvmStatic
    fun extensionOf(name: String?): String {
        if (name == null) return ""
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }
}
