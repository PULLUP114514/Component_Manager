package com.componentvault.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationQrCodecTest {
    @Test
    fun locationIdentityRoundTripsWithoutEmbeddingInventoryOrName() {
        val payload = LocationQrCodec.encode("抽屉 A/1")
        assertTrue(payload.startsWith("cvl-loc1|"))
        assertEquals("抽屉 A/1", LocationQrCodec.decode(payload))
        assertNull(LocationQrCodec.decode("cvl3|C123|1"))
    }

    @Test
    fun malformedLocationCodeDoesNotResolve() {
        assertTrue(LocationQrCodec.isLocationCode("cvl-loc1|%ZZ"))
        assertNull(LocationQrCodec.decode("cvl-loc1|%ZZ"))
        assertNull(LocationQrCodec.decode("cvl-loc1|"))
    }
}
