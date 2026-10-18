package com.componentvault.android.data

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A location identity only. Names and inventory always come from the current local database. */
internal object LocationQrCodec {
    private const val Prefix = "cvl-loc1|"

    fun encode(locationId: String): String {
        val id = locationId.trim()
        require(id.isNotEmpty() && id.length <= 120) { "Invalid location ID" }
        return Prefix + URLEncoder.encode(id, StandardCharsets.UTF_8.name())
    }

    fun decode(raw: String): String? {
        val value = raw.trim()
        if (!value.startsWith(Prefix)) return null
        val encoded = value.removePrefix(Prefix)
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= 120 && encode(it) == value }
    }

    fun isLocationCode(raw: String): Boolean = raw.trim().startsWith(Prefix)
}
