package com.componentvault.android.data

internal sealed interface BarcodeSelection {
    data object NoMatch : BarcodeSelection
    data class Single(val rawValue: String) : BarcodeSelection
    data class Multiple(val candidates: List<Candidate>) : BarcodeSelection
    data class Candidate(val rawValue: String, val sku: String, val quantity: Int?)
}

internal object ScannedBarcodeSelector {
    fun inventoryCandidates(rawValues: List<String>): BarcodeSelection {
        val candidates = rawValues.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .mapNotNull { raw ->
                if (LocationQrCodec.isLocationCode(raw)) {
                    BarcodeSelection.Candidate(raw, LocationQrCodec.decode(raw) ?: "库位二维码", null)
                } else {
                    runCatching { ComponentImportParser.parseScannedQr(raw) }.getOrNull()?.let {
                        BarcodeSelection.Candidate(raw, it.sku, it.suggestedQuantity)
                    }
                }
            }
            .toList()
        return when (candidates.size) {
            0 -> BarcodeSelection.NoMatch
            1 -> BarcodeSelection.Single(candidates.single().rawValue)
            else -> BarcodeSelection.Multiple(candidates)
        }
    }

    fun importCandidates(rawValues: List<String>): BarcodeSelection {
        val parsed = rawValues.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .mapNotNull { raw ->
                runCatching { ComponentImportParser.parseScannedQr(raw) }.getOrNull()?.let {
                    BarcodeSelection.Candidate(raw, it.sku, it.suggestedQuantity)
                }
            }
            .toList()
        return when (parsed.size) {
            0 -> BarcodeSelection.NoMatch
            1 -> BarcodeSelection.Single(parsed.single().rawValue)
            else -> BarcodeSelection.Multiple(parsed)
        }
    }
}
