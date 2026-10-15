package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.componentvault.android.R
import com.componentvault.android.data.InventoryDatabaseHelper
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.model.StorageLocationSaveIntent
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImportLocationPickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsNamesButReturnsStableIdAndRejectsDuplicateName() {
        val context: Application = RuntimeEnvironment.getApplication()
        val helper = InventoryDatabaseHelper(context)
        helper.close()
        context.deleteDatabase(helper.databaseName)
        val repository = InventoryRepository(context)
        runBlocking {
            assertTrue(repository.saveStorageLocation("LOC-ABC", "电阻抽屉", StorageLocationSaveIntent.Create).isSuccess)
        }
        val locations = runBlocking { repository.loadStorageLocations() }
        val selectedId = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ImportLocationPicker(
                    value = selectedId.value,
                    onValueChange = { selectedId.value = it },
                    storageLocations = locations,
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.importer_select_location)).performClick()
        compose.onNodeWithText("电阻抽屉").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("LOC-ABC", selectedId.value) }
        compose.onNodeWithText("电阻抽屉").assertIsDisplayed()
        compose.onNodeWithText("LOC-ABC").assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.importer_create_location)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("电阻抽屉")
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.onNodeWithText(context.getString(R.string.importer_location_duplicate)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("LOC-ABC", selectedId.value) }
        assertEquals(1, runBlocking { repository.loadStorageLocations().count { it.name == "电阻抽屉" } })
    }
}
