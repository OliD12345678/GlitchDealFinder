package com.example.glitchdealfinder

import java.util.Locale
import java.util.UUID

enum class DealStatus { LIVE, CHECKING, EXPIRED, UNKNOWN }

data class Deal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val price: Double,
    val originalPrice: Double,
    val store: String,
    val url: String,
    val productUrl: String = "",
    val verified: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val status: DealStatus = DealStatus.LIVE, // #1: expiry tracking
    val lastCheckedAt: Long = 0L,             // #1: when we last verified it was still live
    val zipCode: String = "",                 // #10: for BrickSeek in-store deals
    val inStore: Boolean = false              // #10: in-store vs online
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

    val isUnicorn: Boolean
        get() = isGlitch && (price <= 1.00 || discountPercentage >= 95)

    // #4: Absolute dollar savings — what actually matters
    val valueSavings: Double
        get() = if (originalPrice > price && price >= 0) originalPrice - price else 0.0

    val valueSavingsFormatted: String
        get() = if (valueSavings > 0) String.format(Locale.US, "$%.0f", valueSavings) else ""

    // #2: Auto-cart deep link
    val cartUrl: String
        get() {
            val link = productUrl.ifBlank { url }
            // Amazon: extract ASIN → add-to-cart URL
            val asinMatch = Regex("/(?:dp|gp/product)/([A-Z0-9]{10})").find(link)
            if (asinMatch != null) {
                return "https://www.amazon.com/gp/aws/cart/add.html?ASIN.1=${asinMatch.groupValues[1]}&Quantity.1=1"
            }
            // Walmart: extract product ID → add-to-cart
            val walmartMatch = Regex("/ip/(?:[^/]+/)?([0-9]+)").find(link)
            if (walmartMatch != null) {
                return "https://affil.walmart.com/cart/addToCart?items=${walmartMatch.groupValues[1]}"
            }
            // Best Buy: extract SKU → add-to-cart
            val bbMatch = Regex("/site/[^/]+/([0-9]+)\\.p").find(link)
            if (bbMatch != null) {
                return "https://api.bestbuy.com/click/-/${bbMatch.groupValues[1]}/cart"
            }
            return link // fallback to product page
        }

    // #7: Minutes since first seen — for countdown display
    val minutesAlive: Int
        get() = ((System.currentTimeMillis() - timestamp) / 60000).toInt()

    val dedupKey: String
        get() {
            val normalizedTitle = title.lowercase()
                .replace(Regex("[^a-z0-9 ]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(60)
            val domain = productUrl.ifBlank { url }
                .replace(Regex("https?://"), "")
                .substringBefore("/")
                .substringBefore("?")
            return "$domain|$normalizedTitle"
        }
}
