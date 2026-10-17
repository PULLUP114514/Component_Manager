package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.componentvault.android.R
import com.componentvault.android.data.InventoryDatabaseHelper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MovementScanEntryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun recordsScanLabelOpensAndDismissesActionDialogWithoutExternalStringProvider() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        val model = InventoryViewModel(context)
        compose.setContent { MaterialTheme { ComponentVaultApp(model) } }

        compose.onNodeWithText(context.getString(R.string.destination_movements))
            .performClick()
        val scanLabel = context.getString(R.string.movements_action_scan_label)
        val dialogTitle = context.getString(R.string.scan_stock_choose_action)
        val actionBack = context.getString(R.string.action_back)

        compose.onNodeWithText(scanLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_stock_inbound)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_stock_outbound)).assertIsDisplayed()
        compose.onNodeWithText(actionBack).assertIsDisplayed().performClick()
        compose.onNodeWithText(dialogTitle).assertDoesNotExist()

        compose.onNodeWithText(scanLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(dialogTitle).assertIsDisplayed()
    }
}
