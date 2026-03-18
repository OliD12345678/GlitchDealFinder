package com.example.glitchdealfinder

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.*

class DealViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("GlitchDealFinderPrefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient()

    private val _glitchDeals = MutableStateFlow<List<Deal>>(emptyList())
    val glitchDeals: StateFlow<List<Deal>> = _glitchDeals.asStateFlow()

    private val _watchlistDeals = MutableStateFlow<List<Deal>>(emptyList())
    val watchlistDeals: StateFlow<List<Deal>> = _watchlistDeals.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchKeywords = MutableStateFlow(loadKeywords())
    val searchKeywords: StateFlow<Set<String>> = _searchKeywords.asStateFlow()

    private val _lastFoundDeal = MutableStateFlow<Deal?>(null)
    val lastFoundDeal: StateFlow<Deal?> = _lastFoundDeal.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _secondsToNextScan = MutableStateFlow(0)
    val secondsToNextScan: StateFlow<Int> = _secondsToNextScan.asStateFlow()

    private val _webhookUrl = MutableStateFlow(prefs.getString("discord_webhook", "") ?: "")
    val webhookUrl: StateFlow<String> = _webhookUrl.asStateFlow()

    private val processedIds = mutableSetOf<String>()
    private val removedIds = mutableSetOf<String>()

    init {
        startSearching()
    }

    private fun loadKeywords(): Set<String> {
        val saved = prefs.getStringSet("watchlist_keywords", null)
        return saved ?: emptySet()
    }

    private fun saveKeywords(keywords: Set<String>) {
        prefs.edit().putStringSet("watchlist_keywords", keywords).apply()
    }

    fun updateWebhook(url: String) {
        _webhookUrl.value = url
        prefs.edit().putString("discord_webhook", url).apply()
    }

    private fun startSearching() {
        viewModelScope.launch {
            while (true) {
                _isSearching.value = true
                _statusMessage.value = "Hunting for Glitches & Watchlist items..."
                fetchAndVerifyDeals()
                
                _isSearching.value = false
                
                for (i in 120 downTo 1) {
                    _secondsToNextScan.value = i
                    _statusMessage.value = "Next scan in ${i}s..."
                    delay(1000)
                }
            }
        }
    }

    private suspend fun fetchAndVerifyDeals() = withContext(Dispatchers.IO) {
        val feeds = listOf(
            "https://slickdeals.net/newsearch.php?mode=frontpage&searcharea=deals&searchin=first&rss=1",
            "https://slickdeals.net/newsearch.php?searcharea=deals&searchin=first&sort=newest&rss=1",
            "https://www.dealnews.com/c142/Home-Garden/?rss=1",
            "https://www.dealnews.com/c39/Electronics/?rss=1",
            "https://www.techbargains.com/rss",
            "https://www.reddit.com/r/buildapcsales/new/.rss",
            "https://www.reddit.com/r/deals/new/.rss"
        )
        
        val newGlitches = mutableListOf<Deal>()
        val newWatchlistDeals = mutableListOf<Deal>()
        
        for (url in feeds) {
            try {
                _statusMessage.value = "Scanning: ${url.substringAfterLast("/")}..."
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .get()
                
                val items = doc.select("item, entry")
                
                for (item in items) {
                    val guid = item.select("guid, id").text().ifBlank { 
                        item.select("link").attr("href").ifBlank { item.select("link").text() } 
                    }
                    
                    if (processedIds.contains(guid) || removedIds.contains(guid)) continue

                    val title = item.select("title").text()
                    val link = item.select("link").attr("href").ifBlank { item.select("link").text() }
                    
                    val currentKeywords = _searchKeywords.value
                    val matchesWatchList = currentKeywords.any { title.contains(it, true) }

                    // Refined initial filter to exclude shipping-related "FREE" mentions
                    val hasFreeShippingMention = title.contains("free shipping", true) || 
                                               title.contains("free s/h", true) || 
                                               title.contains("free s&h", true) ||
                                               title.contains("free s ", true)
                    
                    val isLikelyGlitch = (title.contains("glitch", true) || 
                                       title.contains("mistake", true) || 
                                       title.contains("pricing error", true) ||
                                       title.contains("85%", true) ||
                                       title.contains("90%", true)) ||
                                       (title.contains("free", true) && !hasFreeShippingMention)

                    if (isLikelyGlitch || matchesWatchList) {
                        _statusMessage.value = "Deep Scanning: ${title.take(15)}..."
                        val verifiedDeal = verifyHistoricalPrice(title, link, guid)
                        
                        if (verifiedDeal != null) {
                            var added = false
                            if (verifiedDeal.isGlitch) {
                                newGlitches.add(verifiedDeal)
                                added = true
                                sendDiscordNotification(verifiedDeal, true)
                            } 
                            
                            if (matchesWatchList) {
                                newWatchlistDeals.add(verifiedDeal)
                                added = true
                            }

                            if (added) processedIds.add(guid)
                        } else if (!matchesWatchList) {
                            processedIds.add(guid)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DealFinder", "Fetch Error from $url: ${e.message}")
            }
        }

        withContext(Dispatchers.Main) {
            if (newGlitches.isNotEmpty()) {
                _glitchDeals.value = (newGlitches + _glitchDeals.value).take(50)
                _lastFoundDeal.value = newGlitches.first()
            }
            if (newWatchlistDeals.isNotEmpty()) {
                _watchlistDeals.value = (newWatchlistDeals + _watchlistDeals.value).take(50)
                if (_lastFoundDeal.value == null) {
                    _lastFoundDeal.value = newWatchlistDeals.first()
                }
            }
        }
    }

    private fun sendDiscordNotification(deal: Deal, isGlitch: Boolean) {
        val url = _webhookUrl.value
        if (url.isBlank() || !isGlitch) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject()
                json.put("username", "Glitch Bot")
                json.put("avatar_url", "https://cdn-icons-png.flaticon.com/512/5971/5971593.png")
                
                val embed = JSONObject()
                embed.put("title", "🚨 GLITCH: " + deal.title)
                embed.put("description", "Found a deal at **${deal.store}**")
                embed.put("url", deal.url)
                embed.put("color", 15158332)
                
                val fields = JSONArray()
                fields.put(JSONObject().apply {
                    put("name", "Price")
                    put("value", if (deal.price <= 0.01) "PENNY/FREE" else "$${String.format(Locale.US, "%.2f", deal.price)}")
                    put("inline", true)
                })
                fields.put(JSONObject().apply {
                    put("name", "Discount")
                    put("value", "${deal.discountPercentage}% OFF")
                    put("inline", true)
                })
                
                embed.put("fields", fields)
                embed.put("footer", JSONObject().apply { put("text", "Glitch Deal Finder TV") })
                
                val embeds = JSONArray()
                embeds.put(embed)
                json.put("embeds", embeds)

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("Discord", "Failed to send notification", e)
            }
        }
    }

    private suspend fun verifyHistoricalPrice(title: String, url: String, id: String): Deal? {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; Fire TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.125 Mobile Safari/537.36")
                .timeout(8000)
                .followRedirects(true)
                .get()

            val store = extractStore(title)
            val currentPrice = parseCurrentPrice(doc, title)
            val originalPrice = parseHistoricalPrice(doc, currentPrice)

            // Refined check for free products vs free shipping
            if (currentPrice <= 0) {
                val hasFreeShippingMention = title.contains("free shipping", true) || 
                                           title.contains("free s/h", true) || 
                                           title.contains("free s&h", true) ||
                                           title.contains("free s ", true)
                
                val titleHasFreeProduct = (title.contains("free", true) && !hasFreeShippingMention) ||
                                         title.contains("$0.00") || title.contains("0.00")
                
                if (!titleHasFreeProduct) return null
            }

            Deal(
                id = id,
                title = title,
                price = currentPrice,
                originalPrice = if (originalPrice > currentPrice) originalPrice else 0.0,
                store = store,
                url = url
            )
        } catch (e: Exception) {
            val hasFreeShippingMention = title.contains("free shipping", true) || 
                                       title.contains("free s/h", true) || 
                                       title.contains("free s&h", true) ||
                                       title.contains("free s ", true)
            
            val isExplicitGlitch = title.contains("$0.01") || title.contains("penny", true) || 
                                  (title.contains("free", true) && !hasFreeShippingMention)
            
            if (isExplicitGlitch) {
                return Deal(id = id, title = title, price = 0.01, originalPrice = 10.0, store = extractStore(title), url = url)
            }
            null
        }
    }

    private fun parseCurrentPrice(doc: Document, title: String): Double {
        val selectors = listOf(".a-offscreen", ".price-characteristic", ".current-price", "[itemprop=price]", ".pd-price", ".price", ".offer-price")
        for (selector in selectors) {
            val element = doc.select(selector).first()
            val text = element?.text() ?: ""
            
            if (text.contains("$") || text.contains(".")) {
                val price = text.replace(Regex("[^\\d.]"), "").toDoubleOrNull()
                if (price != null && price > 0) return price
            }
        }
        
        val priceMatch = Regex("\\$\\d+(\\.\\d{2})?").find(title)
        return priceMatch?.value?.removePrefix("$")?.toDoubleOrNull() ?: 0.0
    }

    private fun parseHistoricalPrice(doc: Document, currentPrice: Double): Double {
        val msrpSelectors = listOf(".a-text-price", ".price-strike", ".was-price", ".list-price", "[data-a-strike=true]", ".basisPrice", ".regular-price", ".strike", ".msrp")
        for (selector in msrpSelectors) {
            val text = doc.select(selector).first()?.text() ?: ""
            if (text.contains("$") || text.contains(".")) {
                val price = text.replace(Regex("[^\\d.]"), "").toDoubleOrNull()
                if (price != null && price > currentPrice) return price
            }
        }
        return 0.0
    }

    private fun extractStore(title: String): String {
        val stores = listOf("Amazon", "Walmart", "Best Buy", "Staples", "Home Depot", "Target", "eBay", "Newegg", "B&H", "Costco", "GameStop", "Adorama", "AliExpress")
        return stores.firstOrNull { title.contains(it, ignoreCase = true) } ?: "Retailer"
    }

    fun addKeyword(keyword: String) {
        if (keyword.isNotBlank()) {
            val updated = _searchKeywords.value + keyword
            _searchKeywords.value = updated
            saveKeywords(updated)
        }
    }

    fun removeKeyword(keyword: String) {
        val updated = _searchKeywords.value - keyword
        _searchKeywords.value = updated
        saveKeywords(updated)
    }
    
    fun removeDeal(deal: Deal) {
        removedIds.add(deal.id)
        _glitchDeals.value = _glitchDeals.value.filter { it.id != deal.id }
        _watchlistDeals.value = _watchlistDeals.value.filter { it.id != deal.id }
    }
    
    fun clearLastDeal() {
        _lastFoundDeal.value = null
    }
}
