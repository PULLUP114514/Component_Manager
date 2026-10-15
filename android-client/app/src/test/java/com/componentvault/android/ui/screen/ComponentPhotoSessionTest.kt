package com.componentvault.android.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.componentvault.android.data.InventoryImageStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ComponentPhotoSessionTest {
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
