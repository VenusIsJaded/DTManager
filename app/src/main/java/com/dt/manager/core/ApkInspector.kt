package com.dt.manager.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads the contents of an APK / XAPK / APKM (all ZIP containers) without
 * fully extracting them to disk. Each entry is parsed lazily.
 *
 * XAPK and APKM bundles contain multiple APK files plus assets / OBB data.
 */
class ApkInspector(val source: File) : Closeable {

    private val zip: ZipFile = ZipFile(source)
    private val entries: MutableList<EntryInfo> = ArrayList()
    private val entryMap: MutableMap<String, EntryInfo> = HashMap()

    init {
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (!e.isDirectory) {
                val info = EntryInfo.from(e)
                entries.add(info)
                entryMap[info.path] = info
            }
        }
        entries.sortBy { it.path }
    }

    val name: String get() = source.name

    fun listEntries(): List<EntryInfo> = entries

    fun listInDirectory(dir: String): List<EntryInfo> {
        var prefix = dir
        prefix = when {
            prefix == "/" || prefix.isEmpty() -> ""
            !prefix.endsWith("/") -> "$prefix/"
            else -> prefix
        }

        val result = ArrayList<EntryInfo>()
        val seenFolders = HashSet<String>()

        for (e in entries) {
            val p = e.path
            if (prefix.isEmpty()) {
                val slash = p.indexOf('/')
                if (slash < 0) {
                    result.add(e)
                } else {
                    val dirName = p.substring(0, slash)
                    if (seenFolders.add(dirName)) {
                        result.add(EntryInfo.virtualFolder(dirName))
                    }
                }
            } else {
                if (p.startsWith(prefix)) {
                    val remainder = p.substring(prefix.length)
                    if (remainder.isEmpty()) continue
                    val slash = remainder.indexOf('/')
                    if (slash < 0) {
                        result.add(e)
                    } else {
                        val dirName = remainder.substring(0, slash)
                        val fullVirtualPath = prefix + dirName
                        if (seenFolders.add(fullVirtualPath)) {
                            result.add(EntryInfo.virtualFolder(fullVirtualPath))
                        }
                    }
                }
            }
        }

        result.sortWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase(Locale.ROOT) }))
        return result
    }

    @Throws(IOException::class)
    fun openStream(path: String): InputStream {
        val e = zip.getEntry(path) ?: throw IOException("Entry not found: $path")
        return zip.getInputStream(e)
    }

    @Throws(IOException::class)
    fun readAll(path: String): ByteArray {
        openStream(path).use { input ->
            ByteArrayOutputStream().use { out ->
                val buf = ByteArray(16384)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        }
    }

    @Throws(IOException::class)
    fun readFileText(path: String): String {
        return String(readAll(path), StandardCharsets.UTF_8)
    }

    fun findEntry(path: String): EntryInfo? = entryMap[path]

    /** Returns true if this ZIP contains a single APK and is itself an APK. */
    fun isPlainApk(): Boolean = source.name.lowercase(Locale.ROOT).endsWith(".apk")
    fun isXapk(): Boolean = source.name.lowercase(Locale.ROOT).endsWith(".xapk")
    fun isApkm(): Boolean = source.name.lowercase(Locale.ROOT).endsWith(".apkm")

    /** Returns paths of APK files inside the bundle (used for install). */
    fun findInnerApks(): List<String> {
        val out = ArrayList<String>()
        for (e in entries) {
            if (e.path.lowercase(Locale.ROOT).endsWith(".apk")) {
                out.add(e.path)
            }
        }
        Collections.sort(out)
        return out
    }

    override fun close() {
        zip.close()
    }

    data class EntryInfo(
        val path: String,
        val size: Long,
        val time: Long,
        val isDirectory: Boolean
    ) {
        val name: String
            get() {
                val slash = path.lastIndexOf('/')
                return if (slash < 0) path else path.substring(slash + 1)
            }

        val parentPath: String
            get() {
                val slash = path.lastIndexOf('/')
                return if (slash < 0) "" else path.substring(0, slash)
            }

        companion object {
            @JvmStatic
            fun from(e: ZipEntry): EntryInfo {
                return EntryInfo(e.name, e.size, e.time, e.isDirectory)
            }

            @JvmStatic
            fun virtualFolder(path: String): EntryInfo {
                return EntryInfo(path, 0, 0, true)
            }
        }
    }
}
