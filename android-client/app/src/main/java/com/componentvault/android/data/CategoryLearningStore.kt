package com.componentvault.android.data

import android.content.Context
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportFieldOrigin
import java.util.Locale

/** A confirmed correction for one official classification path, shared by future SKUs. */
internal class CategoryLearningStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "official_category_corrections", Context.MODE_PRIVATE,
    )

    fun learn(candidate: ComponentImportCandidate, correctedCategory: String) {
        val path = officialPath(candidate) ?: return
        val correction = correctedCategory.trim()
        if (correction.isBlank()) return
        preferences.edit().putString(key(path), correction).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun apply(candidate: ComponentImportCandidate): ComponentImportCandidate {
        if (candidate.fieldOrigins.category in setOf(
                ComponentImportFieldOrigin.User, ComponentImportFieldOrigin.Learned,
            )) return candidate
        val path = officialPath(candidate) ?: return candidate
        val correction = preferences.getString(key(path), null)?.takeIf(String::isNotBlank)
            ?: return candidate
        return candidate.copy(
            category = correction,
            fieldOrigins = candidate.fieldOrigins.copy(category = ComponentImportFieldOrigin.Learned),
        )
    }

    private fun officialPath(candidate: ComponentImportCandidate): String? = candidate.notes
        .firstOrNull { it.startsWith(OFFICIAL_PATH_PREFIX) }
        ?.removePrefix(OFFICIAL_PATH_PREFIX)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun key(path: String): String = path.split('/')
        .joinToString("/") { it.trim().lowercase(Locale.ROOT) }

    private companion object {
        const val OFFICIAL_PATH_PREFIX = "官方分类路径："
    }
}
