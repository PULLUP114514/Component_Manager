package com.componentvault.android.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.componentvault.android.R
import com.componentvault.android.data.ApkDownloadPhase
import com.componentvault.android.data.ApkDownloadState
import com.componentvault.android.data.ApkUpdateDownloader
import com.componentvault.android.data.GitHubReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One persistent update task, independent of the latest check result or selected channel. */
@Composable
internal fun ApkUpdateDownloadPane(release: GitHubReleaseInfo?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val downloader = remember(context) { ApkUpdateDownloader(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ApkDownloadState(ApkDownloadPhase.Idle)) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun perform(errorResource: Int, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                operation()
                state = withContext(Dispatchers.IO) { downloader.snapshot() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = errorResource
            } finally {
                busy = false
            }
        }
    }

    fun install() = perform(R.string.update_install_failed) {
        val intent = withContext(Dispatchers.IO) { downloader.installIntent() }
        context.startActivity(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (downloader.canInstall()) install() else error = R.string.update_install_permission_needed
    }

    LaunchedEffect(downloader, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    state = withContext(Dispatchers.IO) { downloader.snapshot() }
                    loaded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    loaded = true
                    error = R.string.update_download_unavailable
                    break
                }
                delay(1_000)
            }
        }
    }

    val active = state.phase == ApkDownloadPhase.Downloading || state.phase == ApkDownloadPhase.Paused
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.phase != ApkDownloadPhase.Idle) {
            Text(stringResource(R.string.update_download_target, state.tagName), style = MaterialTheme.typography.titleSmall)
            when (state.phase) {
                ApkDownloadPhase.Downloading, ApkDownloadPhase.Paused -> {
                    if (state.totalBytes > 0) {
                        val progress = (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_download_progress,
                            (progress * 100).toInt(), megabytes(state.downloadedBytes), megabytes(state.totalBytes)))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_download_waiting, megabytes(state.downloadedBytes)))
                    }
                    Text(stringResource(if (state.phase == ApkDownloadPhase.Paused)
                        R.string.update_download_paused else R.string.update_download_background),
                        style = MaterialTheme.typography.bodySmall)
                }
                ApkDownloadPhase.Ready -> Text(stringResource(R.string.update_download_ready))
                ApkDownloadPhase.Failed -> Text(
                    stringResource(R.string.update_download_failed, state.reason),
                    color = MaterialTheme.colorScheme.error,
                )
                ApkDownloadPhase.Idle -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.phase == ApkDownloadPhase.Ready) {
                    Button(enabled = !busy, onClick = {
                        if (downloader.canInstall()) install()
                        else perform(R.string.update_install_failed) {
                            permissionLauncher.launch(downloader.permissionIntent())
                        }
                    }) { Text(stringResource(R.string.update_install_action)) }
                }
                if (state.phase == ApkDownloadPhase.Failed) {
                    Button(enabled = !busy, onClick = {
                        perform(R.string.update_download_unavailable) { withContext(Dispatchers.IO) { downloader.retry() } }
                    }) { Text(stringResource(R.string.update_retry_action)) }
                }
                OutlinedButton(enabled = !busy, onClick = {
                    perform(R.string.update_download_unavailable) { withContext(Dispatchers.IO) { downloader.cancel() } }
                }) { Text(stringResource(if (active) R.string.update_cancel_action else R.string.update_clear_action)) }
            }
        }
        if (release?.apkUrl != null && (state.phase == ApkDownloadPhase.Idle || state.tagName != release.tagName)) {
            Button(enabled = loaded && !busy && !active, modifier = Modifier.fillMaxWidth(), onClick = {
                perform(R.string.update_download_unavailable) { withContext(Dispatchers.IO) { downloader.start(release) } }
            }) { Text(stringResource(R.string.settings_update_download_apk)) }
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.update_error_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes.coerceAtLeast(0) / 1048576.0)
