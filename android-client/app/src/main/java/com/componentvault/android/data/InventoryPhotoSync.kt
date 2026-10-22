package com.componentvault.android.data

import android.content.Context
import com.componentvault.android.model.SyncConfiguration
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Photo bytes are independent of the inventory cursor. Every run derives remaining work. */
internal class InventoryPhotoSync(private val context: Context) {
    private val state = context.getSharedPreferences("photo-sync", Context.MODE_PRIVATE)
    private val store = InventoryImageStore(context)

    fun keys(): Set<String> {
        val helper = InventoryDatabaseHelper(context)
        return try {
            helper.readableDatabase.rawQuery("SELECT description FROM components WHERE deleted = 0", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) store.key(cursor.getString(0).orEmpty())?.let { add(it) } }
            }
        } finally { helper.close() }
    }

    fun summary(configured: Boolean = true): String {
        val keys = keys()
        val available = keys.filterTo(mutableSetOf()) { store.hasFile(it) }
        val local = available.size
        val acknowledged = state.getStringSet("ack", emptySet()).orEmpty()
        val pending = keys.count { it !in available || it !in acknowledged }
        val status = when {
            !configured -> "照片已在本机保存；尚未配置服务器同步"
            running.get() -> "照片传输中"
            state.getString("state", "") == "running" -> "上次照片传输已中断，请同步重试"
            state.getString("state", "") == "unsupported" -> "服务器暂不支持照片同步"
            state.getInt("errors", 0) > 0 -> "照片部分失败，请同步重试"
            pending > 0 -> "照片待同步"
            keys.isEmpty() -> "尚无元件照片"
            else -> "照片同步完成"
        }
        return "$status；本机可用 $local/${keys.size}，待处理 $pending；上次上传 ${state.getInt("uploaded", 0)}、下载 ${state.getInt("downloaded", 0)}、失败 ${state.getInt("errors", 0)}。" +
            state.getString("last_success", "")?.takeIf { it.isNotBlank() }?.let { " 上次成功：$it" }.orEmpty() +
            state.getString("issue", "")?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    }

    fun clearDownloads() {
        check(!running.get()) { "照片正在同步，请稍后清理。" }
        store.clearDownloads()
        state.edit().putString("state", "pending").putString("issue", "下载缓存已清理；请同步重新下载，原始照片仍保留。").commit()
    }

    fun sync(settings: SyncConfiguration, serverId: String, accountId: String, supported: Boolean,
             checkBinding: () -> Unit, manifest: () -> JSONObject): Pair<Boolean, String> {
        checkBinding()
        val binding = "$serverId/$accountId"
        if (state.getString("binding", "") != binding) state.edit().clear().putString("binding", binding).commit()
        val keys = keys()
        store.pruneDownloads(keys)
        if (!supported) {
            state.edit().putString("state", "unsupported").commit()
            return false to " 照片未同步：服务器暂不支持照片同步。"
        }
        var uploaded = 0; var downloaded = 0; var errors = 0
        val ack = mutableSetOf<String>()
        running.set(true)
        state.edit().putString("state", "running").putString("issue", "").commit()
        var lastIssue = ""
        fun save(issue: String = lastIssue) {
            lastIssue = issue
            state.edit().putInt("uploaded", uploaded).putInt("downloaded", downloaded).putInt("errors", errors)
                .putStringSet("ack", ack.toSet()).putString("issue", issue).commit()
        }
        return try {
            val response = manifest()
            check(response.optInt("photo_protocol") == 1) { "照片同步协议不受支持。" }
            val photos = response.getJSONArray("photos")
            val available = buildMap<String, Boolean> {
                for (i in 0 until photos.length()) photos.getJSONObject(i).let { put(it.getString("key"), it.getBoolean("available")) }
            }
            for (key in keys) {
                checkBinding()
                try {
                    val bytes = store.read(key)
                    when {
                        available[key] == true && bytes != null -> ack.add(key)
                        available[key] == true -> {
                            val incoming = transfer(settings, accountId, key, null)
                            checkBinding()
                            store.persistDownloaded(key, incoming)
                            downloaded++; ack.add(key)
                        }
                        available.containsKey(key) && bytes != null -> {
                            transfer(settings, accountId, key, bytes)
                            checkBinding()
                            uploaded++; ack.add(key)
                        }
                        else -> error("缺少原始照片 ${key.take(12)}；请在保存该照片的设备同步或重新选择照片。")
                    }
                    save()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    checkBinding()
                    errors++; save(error.message ?: "照片传输失败，请同步重试。")
                }
            }
            checkBinding()
            state.edit().putString("state", if (errors == 0) "complete" else "partial").apply {
                if (errors == 0) putString("last_success", Instant.now().toString())
            }.commit()
            (errors == 0) to " 照片上传 $uploaded、下载 $downloaded、失败 $errors。"
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            errors++; save(error.message ?: "照片同步失败，请重试。")
            state.edit().putString("state", "partial").commit()
            false to " 照片未全部同步：${error.message ?: "请重试"}"
        } finally { running.set(false) }
    }

    private fun transfer(settings: SyncConfiguration, accountId: String, key: String, bytes: ByteArray?): ByteArray {
        val connection = URL("${settings.serverBaseUrl.trimEnd('/')}/sync/photos/$key").openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 60_000
        connection.requestMethod = if (bytes == null) "GET" else "PUT"
        connection.setRequestProperty("Authorization", "Bearer ${settings.apiToken}")
        connection.setRequestProperty("X-Component-Vault-Account-Id", accountId)
        return try {
            if (bytes != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (key.endsWith(".png")) "image/png" else "image/jpeg")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            check(connection.responseCode in 200..299) { "照片传输 HTTP ${connection.responseCode}，请同步重试。" }
            if (bytes != null) ByteArray(0) else connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 5 * 1024 * 1024) { "照片超过 5 MiB。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    companion object { val running = AtomicBoolean(false) }
}
