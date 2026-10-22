package com.componentvault.android.data

import android.content.Context
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object WorkbookImageCodec {
    private const val maxImage = 5 * 1024 * 1024
    private const val maxTotal = 25 * 1024 * 1024
    private val anchor = Regex("<xdr:(?:oneCellAnchor|twoCellAnchor)[\\s\\S]*?<xdr:from>[\\s\\S]*?<xdr:col>(\\d+)</xdr:col>[\\s\\S]*?<xdr:row>(\\d+)</xdr:row>[\\s\\S]*?<a:blip[^>]*r:embed=\"([^\"]+)\"[\\s\\S]*?</xdr:(?:oneCellAnchor|twoCellAnchor)>")
    private val rel = Regex("<Relationship[^>]*Id=\"([^\"]+)\"[^>]*Target=\"([^\"]+)\"[^>]*/?>")

    fun extract(bytes: ByteArray, components: List<WorkbookComponent>, vararg acceptedColumns: Int): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
            val e = zip.nextEntry ?: break
            if (!e.isDirectory && (e.name.startsWith("xl/drawings/") || e.name.startsWith("xl/media/"))) {
                val data = zip.readBytes(); total = Math.addExact(total, data.size)
                require(total <= maxTotal) { "工作簿图片解压总量超过 25 MiB。" }
                entries[e.name.replace('\\','/')] = data
            }
        } }
        val drawing = entries.entries.firstOrNull { it.key.matches(Regex("xl/drawings/drawing\\d+\\.xml")) } ?: return emptyMap()
        val relsName = drawing.key.substringBeforeLast('/') + "/_rels/" + drawing.key.substringAfterLast('/') + ".rels"
        val targets = entries[relsName]?.toString(Charsets.UTF_8)?.let { xml -> rel.findAll(xml).associate { it.groupValues[1] to normalize(drawing.key.substringBeforeLast('/') + "/" + it.groupValues[2]) } }.orEmpty()
        val result = linkedMapOf<String, ByteArray>()
        anchor.findAll(drawing.value.toString(Charsets.UTF_8)).forEach { match ->
            val col = match.groupValues[1].toInt(); val row = match.groupValues[2].toInt()
            if (col in acceptedColumns && row in 1..components.size) {
                val data = entries[targets[match.groupValues[3]]] ?: return@forEach
                validateImage(data)
                result[components[row - 1].id] = data
            }
        }
        return result
    }

    fun validateImage(bytes: ByteArray): String {
        require(bytes.size <= maxImage) { "单张图片超过 5 MiB。" }
        val ext = when {
            bytes.size >= 8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)) -> "png"
            bytes.size >= 3 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() && bytes[2]==0xff.toByte() -> "jpg"
            else -> error("图片必须是 PNG 或 JPEG。")
        }
        val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,o)
        require(o.outWidth in 1..4096 && o.outHeight in 1..4096){"图片尺寸超过 4096×4096 或无法解码。"}
        return ext
    }
    fun marker(bytes:ByteArray):String { val ext=validateImage(bytes); return "本地图片："+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}+"."+ext }
    private fun normalize(path:String):String { val out=mutableListOf<String>();path.split('/').forEach{if(it=="..")out.removeLastOrNull() else if(it.isNotBlank()&&it!=".")out+=it};return out.joinToString("/") }
}

internal class InventoryImageStore(context: Context) {
    private val root = File(context.filesDir, "images").apply { mkdirs() }.canonicalFile
    private val downloads = File(context.filesDir, "photo-downloads").apply { mkdirs() }.canonicalFile
    fun key(description: String): String? = Regex("(?m)^本地图片：([0-9a-f]{64}\\.(?:png|jpg))$").find(description)?.groupValues?.get(1)
    fun persist(bytes: ByteArray): String {
        val value = WorkbookImageCodec.marker(bytes)
        write(root, value.substringAfter('：'), bytes)
        return value
    }
    fun persistDownloaded(name: String, bytes: ByteArray) {
        require(WorkbookImageCodec.marker(bytes).substringAfter('：') == name) { "照片内容与标识不匹配。" }
        write(downloads, name, bytes)
    }
    private fun write(directory: File, name: String, bytes: ByteArray) {
        val target = safe(directory, name)
        if (!target.isFile || runCatching { WorkbookImageCodec.marker(target.readBytes()).substringAfter('：') != name }.getOrDefault(true)) {
            val temp = File(directory, "$name.tmp")
            try { temp.writeBytes(bytes); check(temp.renameTo(target)) { "图片持久化失败。" } }
            finally { temp.delete() }
            revision.value += 1
        }
    }
    fun exists(value: String) = runCatching { safe(root, value.substringAfter('：')).isFile }.getOrDefault(false)
    fun delete(value: String) { runCatching { safe(root, value.substringAfter('：')).delete() } }
    fun resolve(description: String): ByteArray? = key(description)?.let(::read)
    /** Summary only stats files; byte validation belongs to display/transfer. */
    fun hasFile(name: String): Boolean = listOf(root, downloads).any { directory ->
        safe(directory, name).let { it.isFile && it.length() in 1..(5L * 1024 * 1024) }
    }
    fun read(name: String): ByteArray? {
        for (directory in listOf(root, downloads)) {
            val file = safe(directory, name)
            if (file.isFile) {
                val bytes = file.readBytes()
                if (runCatching { WorkbookImageCodec.marker(bytes).substringAfter('：') == name }.getOrDefault(false)) return bytes
            }
        }
        return null
    }
    fun pruneDownloads(keys: Set<String>) { downloads.listFiles()?.filter { it.name !in keys }?.forEach { it.delete() } }
    fun clearDownloads() { downloads.listFiles()?.forEach { it.delete() }; revision.value += 1 }
    companion object { val revision = kotlinx.coroutines.flow.MutableStateFlow(0) }
    private fun safe(directory: File, name: String): File {
        require(name.matches(Regex("[0-9a-f]{64}\\.(png|jpg)")))
        return File(directory, name).canonicalFile.also { require(it.parentFile == directory) }
    }
}
