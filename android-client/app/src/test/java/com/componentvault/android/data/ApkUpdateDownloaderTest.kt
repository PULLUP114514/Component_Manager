package com.componentvault.android.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import com.componentvault.android.BuildConfig
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ApkUpdateDownloaderTest {
    private lateinit var context: Context
    private lateinit var manager: DownloadManager
    private lateinit var shadow: ShadowDownloadManager

    @Before
    fun setUp() {
        ShadowDownloadManager.reset()
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        shadow = Shadow.extract(manager)
        context.getSharedPreferences("apk_update_download", Context.MODE_PRIVATE).edit().clear().commit()
        apkFile().delete()
    }

    @Test
    fun persistsTaskAndReusesSameReleaseAcrossInstances() {
        val release = release("v9.0.0-dev.1")
        ApkUpdateDownloader(context).start(release)
        val request = shadow.getRequest(currentId())
        val requestShadow = Shadow.extract<ShadowDownloadManager.ShadowRequest>(request)
        requestShadow.status = DownloadManager.STATUS_RUNNING
        requestShadow.bytesSoFar = 48
        requestShadow.totalSize = 100

        val recreated = ApkUpdateDownloader(context)
        assertEquals(ApkDownloadState(ApkDownloadPhase.Downloading, release.tagName, 48, 100), recreated.snapshot())
        recreated.start(release)
        assertEquals(1, shadow.requestCount)
        assertTrue(requestShadow.destination.toString().endsWith("/update/component-vault-android-release.apk"))
    }

    @Test
    fun pausedSuccessfulAndFailedStatusesAreVisible() {
        val downloader = ApkUpdateDownloader(context)
        downloader.start(release("v9.0.0-dev.2"))
        val requestShadow = Shadow.extract<ShadowDownloadManager.ShadowRequest>(shadow.getRequest(currentId()))

        requestShadow.status = DownloadManager.STATUS_PAUSED
        assertEquals(ApkDownloadPhase.Paused, downloader.snapshot().phase)

        requestShadow.status = DownloadManager.STATUS_FAILED
        assertEquals(ApkDownloadPhase.Failed, downloader.snapshot().phase)

        requestShadow.status = DownloadManager.STATUS_SUCCESSFUL
        assertEquals(ApkDownloadPhase.Failed, downloader.snapshot().phase)
        apkFile().parentFile!!.mkdirs()
        apkFile().writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(ApkDownloadPhase.Ready, downloader.snapshot().phase)
    }

    @Test
    fun missingTaskIsRetryableAndCancelClearsPersistedStateAndFile() {
        val release = release("v9.0.0-dev.3")
        val downloader = ApkUpdateDownloader(context)
        downloader.start(release)
        manager.remove(currentId())

        assertEquals(ApkDownloadPhase.Failed, ApkUpdateDownloader(context).snapshot().phase)
        downloader.retry()
        assertEquals(1, shadow.requestCount)
        apkFile().parentFile!!.mkdirs()
        apkFile().writeBytes(byteArrayOf(1))
        downloader.cancel()
        assertEquals(ApkDownloadPhase.Idle, ApkUpdateDownloader(context).snapshot().phase)
        assertEquals(0, shadow.requestCount)
        assertFalse(apkFile().exists())
    }

    @Test
    fun replacingReleaseRemovesPreviousTask() {
        val downloader = ApkUpdateDownloader(context)
        downloader.start(release("v9.0.0-dev.4"))
        downloader.start(release("v9.0.0-dev.5"))
        assertEquals(1, shadow.requestCount)
        assertEquals("v9.0.0-dev.5", downloader.snapshot().tagName)
    }

    @Test
    fun readyDownloadProvidesInstallIntentAndPermissionIntentCanReturnResult() {
        val downloader = ApkUpdateDownloader(context)
        downloader.start(release("v9.0.0-dev.6"))
        val requestShadow = Shadow.extract<ShadowDownloadManager.ShadowRequest>(shadow.getRequest(currentId()))
        requestShadow.status = DownloadManager.STATUS_SUCCESSFUL
        apkFile().writeBytes(byteArrayOf(1, 2, 3))

        val install = downloader.installIntent()
        assertEquals(Intent.ACTION_VIEW, install.action)
        assertEquals("application/vnd.android.package-archive", install.type)
        assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(install.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, downloader.permissionIntent().action)
        assertEquals(0, downloader.permissionIntent().flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun installedVersionClearsCompletedUpdateAndDownloadedFile() {
        val downloader = ApkUpdateDownloader(context)
        downloader.start(release("v${BuildConfig.VERSION_NAME}"))
        apkFile().writeBytes(byteArrayOf(1))

        assertEquals(ApkDownloadPhase.Idle, ApkUpdateDownloader(context).snapshot().phase)
        assertEquals(0, shadow.requestCount)
        assertFalse(apkFile().exists())
    }

    private fun release(tag: String) = GitHubReleaseInfo(
        tagName = tag,
        version = requireNotNull(NumericSemanticVersion.parse(tag)),
        publishedAt = "",
        releaseNotes = "",
        pageUrl = "https://github.com/Rafael-Ikaros/Component_Manager/releases/tag/$tag",
        apkUrl = "https://github.com/Rafael-Ikaros/Component_Manager/releases/download/$tag/component-vault-android-release.apk",
    )

    private fun apkFile() = File(
        requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
        "update/component-vault-android-release.apk",
    )

    private fun currentId() = context.getSharedPreferences("apk_update_download", Context.MODE_PRIVATE)
        .getLong("download_id", -1)
}
