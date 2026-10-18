package com.componentvault.android.ui.screen

import android.app.Application
import android.net.Uri
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.data.InventoryWorkbookCodec
import com.componentvault.android.model.OperationResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the document URI transfer and delegates workbook semantics to the repository. */
internal class InventoryBackupOperations(
    private val application: Application,
    private val repository: InventoryRepository,
) {
    suspend fun export(uri: Uri) {
        val bytes = repository.exportInventoryWorkbook()
        withContext(Dispatchers.IO) {
            application.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        }
    }

    suspend fun preview(uri: Uri): InventoryRepository.WorkbookPreview {
        val bytes = withContext(Dispatchers.IO) {
            application.contentResolver.openInputStream(uri)!!.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total = Math.addExact(total, count)
                    require(total <= InventoryWorkbookCodec.MAX_FILE_BYTES) { "备份文件超过 50 MiB。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        return repository.previewInventoryWorkbook(bytes)
    }

    suspend fun restore(preview: InventoryRepository.WorkbookPreview): OperationResult =
        repository.restoreInventoryWorkbook(preview)
}
