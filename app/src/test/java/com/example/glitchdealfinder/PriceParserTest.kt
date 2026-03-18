package com.example.glitchdealfinder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*

class PriceParserTest {

    private fun parsePrice(text: String): Double {
        // This mimics the logic in DealViewModel.parseCurrentPrice
        if (text.contains("$") || text.contains(".")) {
            return text.replace(Regex("[^\\d.]"), "").toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    @Test
    fun testGlitchLogic() {
        val title = "FLASHFORGE AD5X Multi-Color 3D Printer"
        val wasPrice = 399.0
        
        // Test 1: Ensure model numbers like AD5X don't get parsed as prices
        val modelNumberText = "AD5X"
        val parsedModelPrice = parsePrice(modelNumberText)
        assertEquals("Model number should not be parsed as price", 0.0, parsedModelPrice, 0.001)
        
        // Test 2: Ensure actual prices are parsed correctly
        val realPriceText = "$265.00"
        val parsedRealPrice = parsePrice(realPriceText)
        assertEquals("Real price should be parsed correctly", 265.0, parsedRealPrice, 0.001)
    }

    @Test
    fun testIsGlitchFix() {
        // 1. Price is 0.0, but title doesn't say FREE -> Should NOT be a glitch
        val deal1 = Deal(title = "3D Printer", price = 0.0, originalPrice = 399.0, store = "Test", url = "")
        assertEquals("0.0 price without 'free' in title should not be a glitch", false, deal1.isGlitch)

        // 2. Price is 0.0, title SAYS free -> SHOULD be a glitch
        val deal2 = Deal(title = "FREE 3D Printer", price = 0.0, originalPrice = 399.0, store = "Test", url = "")
        assertEquals("0.0 price with 'free' in title SHOULD be a glitch", true, deal2.isGlitch)

        // 3. Price is actually a penny -> SHOULD be a glitch
        val deal3 = Deal(title = "Penny Deal", price = 0.01, originalPrice = 10.0, store = "Test", url = "")
        assertEquals("Actual penny deal should be a glitch", true, deal3.isGlitch)
        
        // 4. Actual massive discount (e.g. $3 vs $300) -> SHOULD be a glitch
        val deal4 = Deal(title = "Cheap Item", price = 3.0, originalPrice = 300.0, store = "Test", url = "")
        assertEquals("99% off should be a glitch", true, deal4.isGlitch)
    }
}
