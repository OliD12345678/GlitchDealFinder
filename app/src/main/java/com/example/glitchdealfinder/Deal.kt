package com.example.glitchdealfinder

import java.util.UUID

data class Deal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val price: Double,
    val originalPrice: Double,
    val store: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val discountPercentage: Int
        get() = if (originalPrice > 0 && price >= 0) {
            (((originalPrice - price) / originalPrice) * 100).toInt().coerceIn(0, 100)
        } else 0

    val isGlitch: Boolean
        get() {
            // If price is 0 or less, only count as glitch if "free" is explicitly in title
            // This prevents "Price Not Found" from triggering 100% off alerts
            if (price <= 0) {
                return title.contains("free", ignoreCase = true) || title.contains("0.00")
            }
            
            // Real glitch: Massive discount or literal penny/sub-dollar deal
            return (discountPercentage >= 85) || (price > 0 && price <= 0.10)
        }
}
