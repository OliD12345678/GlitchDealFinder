package com.example.glitchdealfinder

import java.util.UUID

data class Deal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val price: Double,
    val originalPrice: Double,
    val store: String,
    val url: String,
    val productUrl: String = "",  // #2: actual retailer URL (may differ from deal page URL)
    val verified: Boolean = true, // #6: false = price couldn't be verified, show as "unverified"
    val timestamp: Long = System.currentTimeMillis()
) {
    val discountPercentage: Int
        get() = if (originalPrice > 0 && price >= 0) {
            (((originalPrice - price) / originalPrice) * 100).toInt().coerceIn(0, 100)
        } else 0

    val isGlitch: Boolean
        get() {
            if (price <= 0) {
                return title.contains("free", ignoreCase = true) || title.contains("0.00")
            }
            return (discountPercentage >= 85) || (price > 0 && price <= 0.10)
        }

    // #8: Normalized key for cross-source dedup
    val dedupKey: String
        get() {
            val normalizedTitle = title.lowercase()
                .replace(Regex("[^a-z0-9 ]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(60)
            // Use product URL domain + truncated title as dedup key
            val domain = productUrl.ifBlank { url }
                .replace(Regex("https?://"), "")
                .substringBefore("/")
                .substringBefore("?")
            return "$domain|$normalizedTitle"
        }
}
