package com.componentvault.android.data

import android.app.Application
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportFieldOrigin
import com.componentvault.android.model.ComponentImportFieldOrigins
import com.componentvault.android.model.ComponentImportSourceType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CategoryLearningStoreTest {
    @Test
    fun confirmedPathCorrectionAppliesToDifferentSkuOnlyOnSamePath() {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("official_category_corrections", 0).edit().clear().commit()
        val store = CategoryLearningStore(context)
        val path = "Integrated Circuits (ICs) / Memory / Flash"
        val original = candidate("C100", path)
        store.learn(original, "闪存")

        val matching = store.apply(candidate("C200", path.uppercase()))
        assertEquals("闪存", matching.category)
        assertEquals(ComponentImportFieldOrigin.Learned, matching.fieldOrigins.category)
        assertEquals("Other", store.apply(candidate("C300", "Integrated Circuits (ICs) / Logic / Gates")).category)
        assertEquals("手动类别", store.apply(candidate("C400", path).copy(
            category = "手动类别",
            fieldOrigins = ComponentImportFieldOrigins(category = ComponentImportFieldOrigin.User),
        )).category)
        store.learn(matching, "闪存芯片")
        assertEquals("闪存芯片", CategoryLearningStore(context).apply(candidate("C500", path)).category)
        store.clear()
        assertEquals("Other", store.apply(candidate("C600", path)).category)
    }

    private fun candidate(sku: String, path: String) = ComponentImportCandidate(
        sourceType = ComponentImportSourceType.JlcQr,
        rawPayload = sku,
        sourceLabel = "JLC",
        sku = sku,
        category = "Other",
        notes = listOf("官方分类路径：$path"),
        fieldOrigins = ComponentImportFieldOrigins(category = ComponentImportFieldOrigin.PublicWeb),
    )
}
