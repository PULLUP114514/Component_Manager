package com.componentvault.android.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.componentvault.android.BuildConfig
import java.io.File

internal enum class ApkDownloadPhase { Idle, Downloading, Paused, Ready, Failed }

internal data class ApkDownloadState(
    val phase: ApkDownloadPhase,
    val tagName: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val reason: Int = 0,
)

/** Holds one update download across activity and process recreation. */
internal class ApkUpdateDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val downloads = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val preferences = appContext.getSharedPreferences("apk_update_download", Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): ApkDownloadState {
        val tag = preferences.getString(KEY_TAG, null) ?: return ApkDownloadState(ApkDownloadPhase.Idle)
        val installed = NumericSemanticVersion.parse(BuildConfig.VERSION_NAME)
        val target = NumericSemanticVersion.parse(tag)
        if (installed != null && target != null && installed >= target) {
            clear()
            return ApkDownloadState(ApkDownloadPhase.Idle)
        }

        val id = preferences.getLong(KEY_ID, -1)
        if (id < 0) return ApkDownloadState(ApkDownloadPhase.Failed, tag)
        val cursor = downloads.query(DownloadManager.Query().setFilterById(id))
            ?: return ApkDownloadState(ApkDownloadPhase.Failed, tag)
        cursor.use {
            if (!it.moveToFirst()) {
                forgetTask()
                return ApkDownloadState(ApkDownloadPhase.Failed, tag)
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val phase = when (status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> ApkDownloadPhase.Downloading
                DownloadManager.STATUS_PAUSED -> ApkDownloadPhase.Paused
                DownloadManager.STATUS_SUCCESSFUL -> if (apkFile().isFile) ApkDownloadPhase.Ready else ApkDownloadPhase.Failed
                else -> ApkDownloadPhase.Failed
            }
            return ApkDownloadState(phase, tag, downloaded, total, reason)
        }
    }

    @Synchronized
    fun start(release: GitHubReleaseInfo): ApkDownloadState {
        val url = requireNotNull(release.apkUrl) { "Release has no Android APK" }
        require(GitHubReleaseParser.isTrustedApkUrl(url, release.tagName)) { "Untrusted Android APK URL" }
        val current = snapshot()
        if (current.tagName == release.tagName && preferences.getString(KEY_URL, null) == url &&
            current.phase in setOf(ApkDownloadPhase.Downloading, ApkDownloadPhase.Paused, ApkDownloadPhase.Ready)
        ) return current

        clear()
        val directory = requireNotNull(apkFile().parentFile)
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot create Android update download directory"
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Component Vault ${release.tagName}")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "$UPDATE_DIR/$APK_NAME")
        val id = downloads.enqueue(request)
        preferences.edit().putString(KEY_TAG, release.tagName).putString(KEY_URL, url).putLong(KEY_ID, id).apply()
        return ApkDownloadState(ApkDownloadPhase.Downloading, release.tagName)
    }

    @Synchronized
    fun retry(): ApkDownloadState {
        val tag = requireNotNull(preferences.getString(KEY_TAG, null)) { "No update download to retry" }
        val url = requireNotNull(preferences.getString(KEY_URL, null)) { "No update URL to retry" }
        val version = requireNotNull(NumericSemanticVersion.parse(tag)) { "Invalid update version" }
        return start(GitHubReleaseInfo(tag, version, "", "", "", url))
    }

    @Synchronized
    fun cancel() = clear()

    @Synchronized
    fun installIntent(): Intent {
        check(snapshot().phase == ApkDownloadPhase.Ready) { "Android APK is not ready" }
        val id = preferences.getLong(KEY_ID, -1)
        val uri = requireNotNull(downloads.getUriForDownloadedFile(id)) { "Downloaded Android APK is unavailable" }
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        .setData(Uri.parse("package:${appContext.packageName}"))

    private fun forgetTask() {
        preferences.edit().remove(KEY_ID).apply()
        apkFile().delete()
    }

    private fun clear() {
        val id = preferences.getLong(KEY_ID, -1)
        if (id >= 0) downloads.remove(id)
        preferences.edit().clear().apply()
        apkFile().delete()
    }

    private fun apkFile(): File = File(
        requireNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
        "$UPDATE_DIR/$APK_NAME",
    )

    private companion object {
        const val KEY_ID = "download_id"
        const val KEY_TAG = "tag"
        const val KEY_URL = "url"
        const val UPDATE_DIR = "update"
        const val APK_NAME = "component-vault-android-release.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
