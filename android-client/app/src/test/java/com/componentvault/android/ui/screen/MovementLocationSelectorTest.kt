package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.componentvault.android.model.StorageLocationRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MovementLocationSelectorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun showsNamesAndSubmitsStableIdWithoutDeletedOptions() {
        var selected = ""
        compose.setContent {
            MaterialTheme {
                MovementLocationSelector("Location", "LOC-A", listOf(
                    StorageLocationRecord("LOC-A", "Drawer A", "now"),
                    StorageLocationRecord("LOC-B", "Drawer B", "now"),
                    StorageLocationRecord("LOC-DELETED", "Deleted bin", "now", true),
                )) { selected = it }
            }
        }
        compose.onNodeWithText("Drawer A").assertIsDisplayed().performClick()
        compose.onNodeWithText("LOC-A").assertDoesNotExist()
        compose.onNodeWithText("Deleted bin").assertDoesNotExist()
        compose.onNodeWithText("Drawer B").performClick()
        compose.runOnIdle { assertEquals("LOC-B", selected) }
    }
}
