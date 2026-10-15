package com.componentvault.android.model

import java.util.UUID

/** Internal inventory identifier; deliberately distinct from supplier C-number SKUs. */
object LocalComponentSku {
    fun ensure(value: String): String = value.trim().ifBlank {
        "CV-" + UUID.randomUUID().toString().replace("-", "").uppercase(java.util.Locale.ROOT)
    }
}