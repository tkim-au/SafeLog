package com.safelog.app.live

import com.safelog.core.ledger.KeyValueStore
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The car's local ledger: one small JSON file per key under filesDir/safelog (atomic replace on write).
 * Raw trip recordings ("raw/...") are large CSV text, so they are kept gzip-compressed on disk (<key>.gz).
 */
class FileStore(private val root: File) : KeyValueStore {
    init { root.mkdirs() }

    private fun zipped(key: String) = key.startsWith(RAW)

    private fun file(key: String): File {
        require(!key.contains("..")) { "bad key $key" }
        return File(root, if (zipped(key)) "$key.gz" else key)
    }

    @Synchronized override fun read(key: String): String? {
        val f = file(key).takeIf { it.isFile } ?: return null
        return if (zipped(key)) GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() } else f.readText()
    }

    @Synchronized override fun write(key: String, value: String) {
        val f = file(key)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        if (zipped(key)) GZIPOutputStream(tmp.outputStream()).bufferedWriter(Charsets.UTF_8).use { it.write(value) } else tmp.writeText(value)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized override fun delete(key: String) { file(key).delete() }

    @Synchronized override fun list(prefix: String): List<String> {
        require(!prefix.contains("..")) { "bad prefix $prefix" }
        val dir = File(root, prefix.substringBeforeLast('/', ""))
        return dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).invariantSeparatorsPath.let { p -> if (zipped(p)) p.removeSuffix(".gz") else p } }
            .filter { it.startsWith(prefix) }
    }

    private companion object { const val RAW = "raw/" }
}
