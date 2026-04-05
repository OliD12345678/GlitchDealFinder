package com.example.glitchdealfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PriceParserTest {

    private fun parsePrice(text: String): Double {
        if (text.contains("$") || text.contains(".")) {
            return text.replace(Regex("[^\\d.]"), "").toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    @Test
    fun testGlitchLogic() {
        val title = "FLASHFORGE AD5X Multi-Color 3D Printer"

        val modelNumberText = "AD5X"
        val parsedModelPrice = parsePrice(modelNumberText)
        assertEquals("Model number should not be parsed as price", 0.0, parsedModelPrice, 0.001)

        val realPriceText = "$265.00"
        val parsedRealPrice = parsePrice(realPriceText)
        assertEquals("Real price should be parsed correctly", 265.0, parsedRealPrice, 0.001)
    }

    @Test
    fun testIsGlitchFix() {
        val deal1 = Deal(title = "3D Printer", price = 0.0, originalPrice = 399.0, store = "Test", url = "")
        assertEquals("0.0 price without 'free' in title should not be a glitch", false, deal1.isGlitch)

        val deal2 = Deal(title = "FREE 3D Printer", price = 0.0, originalPrice = 399.0, store = "Test", url = "")
        assertEquals("0.0 price with 'free' in title SHOULD be a glitch", true, deal2.isGlitch)

        val deal3 = Deal(title = "Penny Deal", price = 0.01, originalPrice = 10.0, store = "Test", url = "")
        assertEquals("Actual penny deal should be a glitch", true, deal3.isGlitch)

        val deal4 = Deal(title = "Cheap Item", price = 3.0, originalPrice = 300.0, store = "Test", url = "")
        assertEquals("99% off should be a glitch", true, deal4.isGlitch)
    }

    @Test
    fun testUnverifiedDeal() {
        val verified = Deal(title = "Test", price = 0.01, originalPrice = 10.0, store = "Amazon", url = "http://test.com", verified = true)
        val unverified = Deal(title = "Test", price = 0.01, originalPrice = 10.0, store = "Amazon", url = "http://test.com", verified = false)
        assertEquals("Verified deal should be a glitch", true, verified.isGlitch)
        assertEquals("Unverified deal should still be a glitch (isGlitch doesn't check verified)", true, unverified.isGlitch)
        assertEquals("Verified flag should be true", true, verified.verified)
        assertEquals("Verified flag should be false", false, unverified.verified)
    }

    @Test
    fun testDedupKey() {
        // Same product from different sources should have similar dedup keys
        val deal1 = Deal(title = "RTX 4090 $999 at Amazon", price = 999.0, originalPrice = 1599.0, store = "Amazon", url = "https://slickdeals.net/f/123", productUrl = "https://amazon.com/dp/B123")
        val deal2 = Deal(title = "RTX 4090 $999 at Amazon", price = 999.0, originalPrice = 1599.0, store = "Amazon", url = "https://reddit.com/r/deals/xyz", productUrl = "https://amazon.com/dp/B123")
        assertEquals("Same product URL + title should produce same dedup key", deal1.dedupKey, deal2.dedupKey)

        // Different products should have different keys
        val deal3 = Deal(title = "RTX 3080 $499 at Newegg", price = 499.0, originalPrice = 699.0, store = "Newegg", url = "https://newegg.com/123")
        assertNotEquals("Different products should have different dedup keys", deal1.dedupKey, deal3.dedupKey)
    }
}
