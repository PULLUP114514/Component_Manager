package com.componentvault.android.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.componentvault.android.data.InventoryImageStore
import com.componentvault.android.model.ComponentDraft
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ComponentPhotoSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackUsesEditorDismissPath() {
        var dismissed = false
        val mode = InventoryLayoutMode(
            widthClass = InventoryWidthClass.Compact,
            supportsListDetail = false,
            formPresentation = InventoryFormPresentation.FullScreenRoute,
            secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute,
        )
        compose.setContent {
            ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                ComponentEditorSurface(
                    existing = null,
                    initialDraft = ComponentDraft(null, "C123", "Part", "IC", "SOT", "", "", 1, 0),
                    layoutMode = mode,
                    onDismiss = { dismissed = true },
                    onSave = { _, _ -> },
                )
            }
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(dismissed)
    }

    @Test
    fun adoptedPhotoSurvivesStateRestorationAndExplicitCancelDeletesOnlyNewPhoto() {
        val context: Application = RuntimeEnvironment.getApplication()
        val store = InventoryImageStore(context)
        val original = store.persist(png(Color.GREEN))
        val adopted = store.persist(png(Color.BLUE))
        val tester = StateRestorationTester(compose)
        var session: ComponentPhotoSession? = null
        tester.setContent {
            session = rememberComponentPhotoSession("component-1", original)
        }
        compose.runOnIdle { session!!.recordCreated(adopted) }
        val beforeRestore = session
        tester.emulateSavedInstanceStateRestore()
        assertNotSame(beforeRestore, session)
        assertTrue(store.exists(adopted))
        compose.runOnIdle { session!!.finish(adopted, committed = false) }
        assertFalse(store.exists(adopted))
        assertTrue(store.exists(original))
        store.delete(original)
    }

    @Test
    fun committedPhotoRemainsAndCancelledNewPhotoIsRemoved() {
        val context: Application = RuntimeEnvironment.getApplication()
        val store = InventoryImageStore(context)
        val committedMarker = store.persist(png(Color.RED))
        val committedSession = ComponentPhotoSession(context, "")
        committedSession.recordCreated(committedMarker)
        committedSession.finish(committedMarker, committed = true)
        assertTrue(store.exists(committedMarker))

        val cancelledMarker = store.persist(png(Color.BLUE))
        val cancelledSession = ComponentPhotoSession(context, committedMarker)
        cancelledSession.recordCreated(cancelledMarker)
        cancelledSession.finish(cancelledMarker, committed = false)
        assertFalse(store.exists(cancelledMarker))
        assertTrue(store.exists(committedMarker))
        store.delete(committedMarker)
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
