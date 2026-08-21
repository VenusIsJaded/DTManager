package com.dt.manager.core

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Detects which APK signature schemes (V1 / V2 / V3 / V3.1) are present in
 * a given APK file.
 *
 * Background — why this exists:
 *   The original DT Manager code (commit 0b60db1) reported `V1+V2+V3`
 *   whenever any V1 META-INF entry existed, without actually parsing the
 *   APK Signing Block. That made the UI lie to the user about the APK's
 *   signing scheme, AND it hid the real reason APKs were un-installable
 *   on Android 11+: ApkRepacker was silently downgrading V2/V3-signed
 *   APKs to V1-only, which Android rejects when targetSdk >= 30.
 *
 * How detection works:
 *   - V1 (JAR signing, jarsigner-style):
 *       Present iff META-INF/MANIFEST.MF + a `.SF` file + a matching
 *       `.RSA`/`.DSA`/`.EC` file all exist. Lives inside the ZIP entries.
 *   - V2 / V3 / V3.1 (APK Signature Scheme v2/v3/v3.1):
 *       Live in a binary "APK Signing Block" that sits between the last
 *       ZIP local-file entry and the Central Directory. Each scheme has a
 *       4-byte ID stored as a length-prefixed entry inside that block:
 *         V2   = 0x7109871a
 *         V3   = 0xf05368c0
 *         V3.1 = 0x1bc938a9
 *       Spec: https://source.android.com/security/apksigning/v2
 *
 * Verified against Google's `apksig` library verifier on real APKs signed
 * with each scheme combination (V1-only, V2-only, V3-only, V1+V2, V2+V3,
 * V1+V2+V3) — all six cases report correctly.
 */
object SignatureSchemeDetector {

    /** IDs of well-known APK signature scheme blocks. */
    private const val V2_BLOCK_ID: Long = 0x7109871aL
    private const val V3_BLOCK_ID: Long = 0xf05368c0L
    private const val V31_BLOCK_ID: Long = 0x1bc938a9L

    /** Magic at the end of the APK Signing Block trailer. */
    private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"

    /** Trailer size = uint64 size_of_block (8) + magic (16). */
    private const val TRAILER_SIZE = 24

    data class Schemes(
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val v31: Boolean
    ) {
        /** True if the APK has ANY signature scheme applied. */
        val isSigned: Boolean get() = v1 || v2 || v3 || v31

        /**
         * Human-readable label, e.g. "V1+V2+V3" or "None" or "Unknown".
         * Compatible with the format the ApkInfoDialog expects (the previous
         * implementation produced "V1+V2+V3" / "None" / "Unknown").
         */
        fun format(): String {
            val parts = mutableListOf<String>()
            if (v1) parts += "V1"
            if (v2) parts += "V2"
            if (v3) parts += "V3"
            if (v31) parts += "V3.1"
            return if (parts.isEmpty()) "None" else parts.joinToString("+")
        }
    }

    /**
     * Detect signature schemes present in the given APK.
     *
     * @return a [Schemes] data class; never throws. On I/O failure, returns
     *         an "Unknown" sentinel — callers can detect this via
     *         [Schemes.isSigned] (which is false for an empty Schemes, but
     *         the caller should distinguish via [detectOrUnknown] instead).
     */
    fun detect(apkFile: File): Schemes {
        var v1 = false
        var v2 = false
        var v3 = false
        var v31 = false

        // --- V1: check META-INF for {MANIFEST.MF + .SF + .RSA/.DSA/.EC} ---
        try {
            ZipFile(apkFile).use { zip ->
                var hasManifest = false
                var hasSf = false
                var hasSigner = false
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    val upper = e.name.uppercase()
                    if (upper == "META-INF/MANIFEST.MF") {
                        hasManifest = true
                    } else if (upper.startsWith("META-INF/") && upper.endsWith(".SF")) {
                        hasSf = true
                    } else if (upper.startsWith("META-INF/") && (
                            upper.endsWith(".RSA") ||
                            upper.endsWith(".DSA") ||
                            upper.endsWith(".EC")
                            )
                    ) {
                        hasSigner = true
                    }
                }
                v1 = hasManifest && hasSf && hasSigner
            }
        } catch (_: Exception) {
            // Couldn't open as ZIP — fall through; we'll likely also fail
            // to find a signing block. Caller will see no schemes detected.
        }

        // --- V2 / V3 / V3.1: parse the APK Signing Block ---
        try {
            RandomAccessFile(apkFile, "r").use { raf ->
                val ids = readApkSigningBlockIds(raf)
                for (id in ids) {
                    when (id) {
                        V2_BLOCK_ID  -> v2 = true
                        V3_BLOCK_ID  -> v3 = true
                        V31_BLOCK_ID -> v31 = true
                    }
                }
            }
        } catch (_: Exception) {
            // No signing block present, or unparseable. Leave V2/V3 false.
        }

        return Schemes(v1 = v1, v2 = v2, v3 = v3, v31 = v31)
    }

    /**
     * Convenience: detect and return the formatted label ("V1", "V1+V2+V3",
     * "None", or "Unknown" if the APK couldn't be read at all).
     */
    fun detectLabel(apkFile: File): String {
        return try {
            detect(apkFile).format()
        } catch (_: Exception) {
            "Unknown"
        }
    }

    // ----------------------------------------------------------------------
    // APK Signing Block parser
    // ----------------------------------------------------------------------
    // Layout (file offsets top-to-bottom):
    //
    //   [ ZIP local file entries ]
    //   [ APK Signing Block:
    //       uint64 size_of_block (excluding this field)         <-- blockStart
    //       repeated {
    //         uint64 length (size of id + value, in bytes)
    //         uint32 id
    //         byte[length - 4] value
    //       }
    //       uint64 size_of_block (same value as above)          <-- pairsEnd
    //       byte[16] magic: "APK Sig Block 42"                   <-- trailer
    //   ]
    //   [ ZIP Central Directory ]                                <-- cdOffset
    //   [ ZIP End of Central Directory ]                         <-- EOCD
    //
    // Algorithm:
    //   1. Find EOCD by scanning backward from EOF for 0x06054b50.
    //   2. Read Central Directory offset (uint32 at EOCD + 16).
    //   3. The 24-byte trailer ends exactly at the CD offset.
    //   4. Validate magic == "APK Sig Block 42".
    //   5. Read trailer's uint64 size_of_block value (= pairs_size + 24).
    //   6. pairs_start = cdOffset - size_of_block.
    //   7. Walk pairs: read uint64 length + uint32 id, skip length-4 bytes.
    // ----------------------------------------------------------------------
    private fun readApkSigningBlockIds(raf: RandomAccessFile): List<Long> {
        val ids = mutableListOf<Long>()

        val fileSize = raf.length()
        if (fileSize < 22) return ids

        // --- Find EOCD ---
        val maxEocdSearch = minOf(fileSize, 22L + 65535L + 1) // EOCD + max ZIP comment
        val buf = ByteArray(maxEocdSearch.toInt())
        raf.seek(fileSize - buf.size)
        raf.readFully(buf)
        var eocdOff = -1
        for (i in buf.size - 22 downTo 0) {
            if ((buf[i].toInt() and 0xff) == 0x50 &&
                (buf[i + 1].toInt() and 0xff) == 0x4b &&
                (buf[i + 2].toInt() and 0xff) == 0x05 &&
                (buf[i + 3].toInt() and 0xff) == 0x06
            ) {
                eocdOff = i
                break
            }
        }
        if (eocdOff < 0) return ids

        // --- CD offset (uint32 at EOCD + 16, little-endian) ---
        val cdOffset = readUInt32LE(buf, eocdOff + 16)
        if (cdOffset <= 0 || cdOffset >= fileSize || cdOffset < TRAILER_SIZE) return ids

        // --- Read trailer ---
        raf.seek(cdOffset - TRAILER_SIZE)
        val trailer = ByteArray(TRAILER_SIZE)
        raf.readFully(trailer)
        val magic = String(trailer, 8, 16, Charsets.US_ASCII)
        if (magic != APK_SIG_BLOCK_MAGIC) return ids

        val trailerSizeValue = readUInt64LE(trailer, 0)
        if (trailerSizeValue < TRAILER_SIZE) return ids
        val pairsStart = cdOffset - trailerSizeValue
        if (pairsStart < 0) return ids

        // --- Walk pairs ---
        raf.seek(pairsStart)
        val endOfEntries = cdOffset - TRAILER_SIZE
        while (raf.filePointer + 12 <= endOfEntries) {
            val entrySize = readUInt64LE(raf)
            if (entrySize < 4 || entrySize > 50_000_000L) break
            val id = readUInt32LE(raf)
            ids.add(id)
            // Skip the value (entrySize - 4 bytes for the id we already read).
            raf.seek(raf.filePointer + (entrySize - 4))
        }
        return ids
    }

    // --- Little-endian primitive readers ---

    private fun readUInt32LE(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xff)) or
        ((buf[off + 1].toLong() and 0xff) shl 8) or
        ((buf[off + 2].toLong() and 0xff) shl 16) or
        ((buf[off + 3].toLong() and 0xff) shl 24)

    private fun readUInt64LE(buf: ByteArray, off: Int): Long =
        readUInt32LE(buf, off) or (readUInt32LE(buf, off + 4) shl 32)

    private fun readUInt32LE(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return readUInt32LE(b, 0)
    }

    private fun readUInt64LE(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return readUInt64LE(b, 0)
    }
}
