package com.componentvault.android.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.InventoryImageStore
import com.componentvault.android.data.WorkbookImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val localImageLine = Regex("(?m)^本地图片：[0-9a-f]{64}\\.(?:png|jpg)$")

/** Owned by the stable parent editor so scrolling a lazy item never discards its photo. */
internal class ComponentPhotoSession(context: Context, openingDescription: String) {
    private val imageStore = InventoryImageStore(context)
    private val openingMarker = localImageLine.find(openingDescription)?.value
    private val createdMarkers = mutableSetOf<String>()
    private var finished = false

    fun recordCreated(marker: String) {
        if (!finished) createdMarkers += marker
    }

    fun finish(currentDescription: String, committed: Boolean) {
        if (finished) return
        finished = true
        val retained = if (committed) localImageLine.find(currentDescription)?.value else null
        createdMarkers.filter { it != openingMarker && it != retained }
            .forEach(imageStore::delete)
    }
}

/** Call once in the editor's stable composable scope, outside LazyColumn items. */
@Composable
internal fun rememberComponentPhotoSession(
    openingDescription: String,
    currentDescription: String,
    isCommitted: () -> Boolean = { false },
): ComponentPhotoSession {
    val context = LocalContext.current
    val session = remember(context, openingDescription) { ComponentPhotoSession(context, openingDescription) }
    val latestDescription = rememberUpdatedState(currentDescription)
    DisposableEffect(session) {
        onDispose { session.finish(latestDescription.value, isCommitted()) }
    }
    return session
}

/** The parent editor owns save/cancel. A picked photo remains in memory until Use is pressed. */
@Composable
internal fun ComponentPhotoEditor(
    description: String,
    onDescriptionChange: (String) -> Unit,
    session: ComponentPhotoSession,
) {
    val context = LocalContext.current
    val imageStore = remember(context) { InventoryImageStore(context) }
    val scope = rememberCoroutineScope()
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentBytes = remember(description) { runCatching { imageStore.resolve(description) }.getOrNull() }
    val previewBytes = pendingBytes ?: currentBytes
    val preview = remember(previewBytes) {
        previewBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    fun stage(bytes: ByteArray) {
        runCatching { WorkbookImageCodec.validateImage(bytes) }
            .onSuccess { pendingBytes = bytes; error = null }
            .onFailure { error = it.message }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= MAX_IMAGE_BYTES) { "图片不能超过 5 MiB。" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: kotlin.error("无法读取图片。")
                }
            }.onSuccess(::stage).onFailure { error = it.message }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) stage(output.toByteArray())
        }
    }

    fun launchCamera() {
        runCatching { camera.launch(null) }.onFailure { error = context.getString(R.string.component_photo_camera_unavailable) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else error = context.getString(R.string.component_photo_camera_permission)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.component_photo_title), style = MaterialTheme.typography.titleSmall)
        if (preview != null) Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = stringResource(R.string.component_photo_title),
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentScale = ContentScale.Fit,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { runCatching { gallery.launch("image/*") }.onFailure { error = it.message } }) {
                Text(stringResource(R.string.component_photo_choose))
            }
            OutlinedButton(enabled = !busy, onClick = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera()
                else cameraPermission.launch(android.Manifest.permission.CAMERA)
            }) {
                Text(stringResource(R.string.component_photo_camera))
            }
        }
        pendingBytes?.let { bytes ->
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        val marker = WorkbookImageCodec.marker(bytes)
                        val created = !imageStore.exists(marker)
                        imageStore.persist(bytes) to created
                    } }
                        .onSuccess { (marker, created) ->
                            if (created) session.recordCreated(marker)
                            onDescriptionChange(description.replace(localImageLine, "").trim()
                                .let { base -> listOf(base, marker).filter(String::isNotBlank).joinToString("\n") })
                            pendingBytes = null
                            error = null
                        }
                        .onFailure { error = it.message }
                    busy = false
                }
            }) { Text(stringResource(R.string.component_photo_use)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.component_photo_local_only), style = MaterialTheme.typography.bodySmall)
    }
}

private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
