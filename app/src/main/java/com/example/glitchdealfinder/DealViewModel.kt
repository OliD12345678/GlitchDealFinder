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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.*
import java.util.concurrent.TimeUnit

class DealViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("GlitchDealFinderPrefs", Context.MODE_PRIVATE)

    // OkHttp with explicit timeouts, connection pool, and cookie jar for retailer sessions
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                cookieStore.getOrPut(host) { mutableListOf() }.apply {
                    // Replace existing cookies with same name
                    val newNames = cookies.map { it.name }.toSet()
                    removeAll { it.name in newNames }
                    addAll(cookies)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .followRedirects(true)
        .build()

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

    // #6: Telegram bot token + chat ID
    private val _telegramBotToken = MutableStateFlow(prefs.getString("telegram_bot_token", "") ?: "")
    val telegramBotToken: StateFlow<String> = _telegramBotToken.asStateFlow()
    private val _telegramChatId = MutableStateFlow(prefs.getString("telegram_chat_id", "") ?: "")
    val telegramChatId: StateFlow<String> = _telegramChatId.asStateFlow()

    // #8: Separate webhook for unicorn-only alerts
    private val _unicornWebhookUrl = MutableStateFlow(prefs.getString("discord_webhook_unicorn", "") ?: "")
    val unicornWebhookUrl: StateFlow<String> = _unicornWebhookUrl.asStateFlow()

    // #5: Stats tracking
    private val _stats = MutableStateFlow(loadStats())
    val stats: StateFlow<DealStats> = _stats.asStateFlow()

    // #10: BrickSeek ZIP code
    private val _zipCode = MutableStateFlow(prefs.getString("brickseek_zip", "") ?: "")
    val zipCode: StateFlow<String> = _zipCode.asStateFlow()

    // Persistent dedup sets
    private val processedIds = loadStringSet("processed_ids").toMutableSet()
    private val removedIds = loadStringSet("removed_ids").toMutableSet()
    private val seenDedupKeys = loadStringSet("seen_dedup_keys").toMutableSet()

    // Per-feed backoff tracking
    private val feedFailCounts = mutableMapOf<String, Int>()

    // Discord rate-limit queue
    private val discordQueue = mutableListOf<Deal>()
    private val discordMutex = Mutex()

    // #1: User-Agent rotation
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    init {
        loadPersistedDeals()
        startSearching()
        startDiscordQueueProcessor()
        startExpiryChecker() // #1: periodic expiry detection
    }

    // ── Stats (#5) ──

    data class DealStats(
        val totalGlitchesFound: Int = 0,
        val totalUnicorns: Int = 0,
        val totalWatchlistHits: Int = 0,
        val bestSavings: Double = 0.0,
        val bestDealTitle: String = "",
        val dealsToday: Int = 0,
        val dealsThisWeek: Int = 0,
        val todayDate: String = ""
    )

    private fun loadStats(): DealStats {
        return DealStats(
            totalGlitchesFound = prefs.getInt("stats_total_glitches", 0),
            totalUnicorns = prefs.getInt("stats_total_unicorns", 0),
            totalWatchlistHits = prefs.getInt("stats_total_watchlist", 0),
            bestSavings = prefs.getFloat("stats_best_savings", 0f).toDouble(),
            bestDealTitle = prefs.getString("stats_best_deal_title", "") ?: "",
            dealsToday = prefs.getInt("stats_deals_today", 0),
            dealsThisWeek = prefs.getInt("stats_deals_week", 0),
            todayDate = prefs.getString("stats_today_date", "") ?: ""
        )
    }

    private fun recordStats(deal: Deal, isWatchlist: Boolean) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val current = _stats.value
        val resetToday = current.todayDate != today
        val newStats = current.copy(
            totalGlitchesFound = current.totalGlitchesFound + if (deal.isGlitch) 1 else 0,
            totalUnicorns = current.totalUnicorns + if (deal.isUnicorn) 1 else 0,
            totalWatchlistHits = current.totalWatchlistHits + if (isWatchlist) 1 else 0,
            bestSavings = maxOf(current.bestSavings, deal.valueSavings),
            bestDealTitle = if (deal.valueSavings > current.bestSavings) deal.title else current.bestDealTitle,
            dealsToday = (if (resetToday) 0 else current.dealsToday) + 1,
            dealsThisWeek = current.dealsThisWeek + 1,
            todayDate = today
        )
        _stats.value = newStats
        prefs.edit()
            .putInt("stats_total_glitches", newStats.totalGlitchesFound)
            .putInt("stats_total_unicorns", newStats.totalUnicorns)
            .putInt("stats_total_watchlist", newStats.totalWatchlistHits)
            .putFloat("stats_best_savings", newStats.bestSavings.toFloat())
            .putString("stats_best_deal_title", newStats.bestDealTitle)
            .putInt("stats_deals_today", newStats.dealsToday)
            .putInt("stats_deals_week", newStats.dealsThisWeek)
            .putString("stats_today_date", newStats.todayDate)
            .apply()
    }

    // ── Settings updates (#6, #8, #10) ──

    fun updateTelegram(botToken: String, chatId: String) {
        _telegramBotToken.value = botToken
        _telegramChatId.value = chatId
        prefs.edit().putString("telegram_bot_token", botToken).putString("telegram_chat_id", chatId).apply()
    }

    fun updateUnicornWebhook(url: String) {
        _unicornWebhookUrl.value = url
        prefs.edit().putString("discord_webhook_unicorn", url).apply()
    }

    fun updateZipCode(zip: String) {
        _zipCode.value = zip
        prefs.edit().putString("brickseek_zip", zip).apply()
    }

    // ── Persistence helpers (#3) ──

    private fun loadStringSet(key: String): Set<String> {
        return prefs.getStringSet(key, null) ?: emptySet()
    }

    private fun persistStringSet(key: String, set: Set<String>) {
        // Keep sets bounded to prevent SharedPreferences bloat
        val bounded = if (set.size > 2000) set.toList().takeLast(1500).toSet() else set
        prefs.edit().putStringSet(key, bounded).apply()
    }

    private fun persistProcessedIds() {
        persistStringSet("processed_ids", processedIds)
    }

    private fun persistRemovedIds() {
        persistStringSet("removed_ids", removedIds)
    }

    private fun persistDedupKeys() {
        persistStringSet("seen_dedup_keys", seenDedupKeys)
    }

    private fun loadPersistedDeals() {
        try {
            val glitchJson = prefs.getString("persisted_glitches", null)
            val watchlistJson = prefs.getString("persisted_watchlist", null)
            if (glitchJson != null) _glitchDeals.value = deserializeDeals(glitchJson)
            if (watchlistJson != null) _watchlistDeals.value = deserializeDeals(watchlistJson)
        } catch (e: Exception) {
            Log.e("DealFinder", "Failed to load persisted deals", e)
        }
    }

    private fun persistDeals() {
        prefs.edit()
            .putString("persisted_glitches", serializeDeals(_glitchDeals.value))
            .putString("persisted_watchlist", serializeDeals(_watchlistDeals.value))
            .apply()
    }

    private fun serializeDeals(deals: List<Deal>): String {
        val arr = JSONArray()
        for (d in deals) {
            arr.put(JSONObject().apply {
                put("id", d.id); put("title", d.title); put("price", d.price)
                put("originalPrice", d.originalPrice); put("store", d.store)
                put("url", d.url); put("productUrl", d.productUrl)
                put("verified", d.verified); put("timestamp", d.timestamp)
                put("status", d.status.name); put("lastCheckedAt", d.lastCheckedAt)
                put("zipCode", d.zipCode); put("inStore", d.inStore)
            })
        }
        return arr.toString()
    }

    private fun deserializeDeals(json: String): List<Deal> {
        val arr = JSONArray(json)
        val list = mutableListOf<Deal>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Deal(
                id = o.getString("id"), title = o.getString("title"),
                price = o.getDouble("price"), originalPrice = o.getDouble("originalPrice"),
                store = o.getString("store"), url = o.getString("url"),
                productUrl = o.optString("productUrl", ""),
                verified = o.optBoolean("verified", true),
                timestamp = o.getLong("timestamp"),
                status = try { DealStatus.valueOf(o.optString("status", "LIVE")) } catch (e: Exception) { DealStatus.LIVE },
                lastCheckedAt = o.optLong("lastCheckedAt", 0),
                zipCode = o.optString("zipCode", ""),
                inStore = o.optBoolean("inStore", false)
            ))
        }
        return list
    }

    // ── Keywords ──

    private fun loadKeywords(): Set<String> {
        return prefs.getStringSet("watchlist_keywords", null) ?: emptySet()
    }

    private fun saveKeywords(keywords: Set<String>) {
        prefs.edit().putStringSet("watchlist_keywords", keywords).apply()
    }

    fun updateWebhook(url: String) {
        _webhookUrl.value = url
        prefs.edit().putString("discord_webhook", url).apply()
    }

    // ── Main scan loop ──

    private fun startSearching() {
        viewModelScope.launch {
            while (true) {
                scanCount++
                _isSearching.value = true
                _statusMessage.value = "Hunting for Glitches & Watchlist items..."
                fetchAndVerifyDeals()

                _isSearching.value = false

                // Faster cycle: 45s between scans (penny deals vanish in minutes)
                for (i in 45 downTo 1) {
                    _secondsToNextScan.value = i
                    _statusMessage.value = "Next scan in ${i}s..."
                    delay(1000)
                }
            }
        }
    }

    // ── Feed definitions (#1: includes Reddit JSON fallback) ──

    private data class FeedSource(
        val url: String,
        val type: String = "rss", // "rss", "reddit_json", or "brickseek"
        val label: String = url.substringAfterLast("/"),
        val tier: Int = 2 // 1 = every scan, 2 = every other scan, 3 = every 3rd scan
    )

    private var scanCount = 0

    private val feeds = listOf(
        // Tier 1: High-velocity glitch sources — scanned every cycle (45s)
        FeedSource("https://slickdeals.net/newsearch.php?searcharea=deals&searchin=first&sort=newest&rss=1", label = "Slickdeals New", tier = 1),
        FeedSource("https://www.reddit.com/r/buildapcsales/new/.json?limit=25", type = "reddit_json", label = "r/buildapcsales", tier = 1),
        FeedSource("https://www.reddit.com/r/deals/new/.json?limit=25", type = "reddit_json", label = "r/deals", tier = 1),
        // Glitch-specific subreddits
        FeedSource("https://www.reddit.com/r/glitchdeals/new/.json?limit=25", type = "reddit_json", label = "r/glitchdeals", tier = 1),
        FeedSource("https://www.reddit.com/r/NintendoSwitchDeals/new/.json?limit=15", type = "reddit_json", label = "r/NintendoSwitchDeals", tier = 1),

        // Tier 2: Standard deal sources — every other scan
        FeedSource("https://slickdeals.net/newsearch.php?mode=frontpage&searcharea=deals&searchin=first&rss=1", label = "Slickdeals Front", tier = 2),
        FeedSource("https://www.dealnews.com/c39/Electronics/?rss=1", label = "DealNews Electronics", tier = 2),
        FeedSource("https://www.reddit.com/r/GameDeals/new/.json?limit=15", type = "reddit_json", label = "r/GameDeals", tier = 2),

        // Tier 3: Slower sources — every 3rd scan
        FeedSource("https://www.dealnews.com/c142/Home-Garden/?rss=1", label = "DealNews Home", tier = 3),
        FeedSource("https://www.techbargains.com/rss", label = "TechBargains", tier = 3),
        FeedSource("https://www.reddit.com/r/frugalmalefashion/new/.json?limit=15", type = "reddit_json", label = "r/frugalmalefashion", tier = 3),
        FeedSource("https://www.reddit.com/r/ThriftStoreHauls/new/.json?limit=10", type = "reddit_json", label = "r/ThriftStoreHauls", tier = 3)
    )

    private suspend fun fetchAndVerifyDeals() = withContext(Dispatchers.IO) {
        val newGlitches = mutableListOf<Deal>()
        val newWatchlistDeals = mutableListOf<Deal>()

        // Tiered scanning: only process feeds whose tier matches this scan cycle
        val activeFeeds = feeds.filter { scanCount % it.tier == 0 }
        Log.d("DealFinder", "Scan #$scanCount: ${activeFeeds.size}/${feeds.size} feeds active")

        // #3: Twitter scan every 3rd cycle (rate-limit friendly)
        if (scanCount % 3 == 0) {
            try {
                _statusMessage.value = "Scanning: Twitter/X..."
                val twitterItems = fetchTwitterDeals()
                for ((guid, title, link) in twitterItems) {
                    if (processedIds.contains(guid) || removedIds.contains(guid)) continue
                    processedIds.add(guid)
                    val verifiedDeal = verifyDeal(title, link, guid)
                    if (verifiedDeal != null && verifiedDeal.isGlitch) {
                        if (!seenDedupKeys.contains(verifiedDeal.dedupKey)) {
                            newGlitches.add(verifiedDeal)
                            seenDedupKeys.add(verifiedDeal.dedupKey)
                            queueDiscordNotification(verifiedDeal)
                            sendTelegramNotification(verifiedDeal)
                            recordStats(verifiedDeal, false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("DealFinder", "Twitter scan error: ${e.message}")
            }
        }

        // #10: BrickSeek scan every 5th cycle
        if (scanCount % 5 == 0 && _zipCode.value.length >= 5) {
            try {
                _statusMessage.value = "Scanning: BrickSeek (${_zipCode.value})..."
                val bsItems = fetchBrickSeekDeals()
                for ((guid, title, link) in bsItems) {
                    if (processedIds.contains(guid)) continue
                    processedIds.add(guid)
                    val deal = Deal(
                        id = guid, title = title, price = 0.0, originalPrice = 0.0,
                        store = "Walmart (In-Store)", url = link, inStore = true,
                        zipCode = _zipCode.value, verified = false
                    )
                    newWatchlistDeals.add(deal)
                }
            } catch (e: Exception) {
                Log.d("DealFinder", "BrickSeek scan error: ${e.message}")
            }
        }

        for (feed in activeFeeds) {
            // #1: Exponential backoff — skip feeds that keep failing
            val failCount = feedFailCounts[feed.url] ?: 0
            if (failCount > 0) {
                val backoffScans = (1 shl failCount.coerceAtMost(5)) // 2, 4, 8, 16, 32 scans
                val skipUntil = prefs.getLong("feed_skip_${feed.url.hashCode()}", 0)
                if (System.currentTimeMillis() < skipUntil) {
                    Log.d("DealFinder", "Skipping ${feed.label} (backoff, ${failCount} failures)")
                    continue
                }
            }

            try {
                _statusMessage.value = "Scanning: ${feed.label}..."

                val items = when (feed.type) {
                    "reddit_json" -> fetchRedditJson(feed.url)
                    else -> fetchRssFeed(feed.url)
                }

                // Reset fail count on success
                feedFailCounts[feed.url] = 0

                for ((guid, title, link) in items) {
                    if (processedIds.contains(guid) || removedIds.contains(guid)) continue

                    val currentKeywords = _searchKeywords.value
                    val matchesWatchList = currentKeywords.any { title.contains(it, true) }

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
                        val verifiedDeal = verifyDeal(title, link, guid)

                        if (verifiedDeal != null) {
                            // #8: Cross-source dedup
                            if (seenDedupKeys.contains(verifiedDeal.dedupKey)) {
                                processedIds.add(guid)
                                continue
                            }

                            var added = false
                            if (verifiedDeal.isGlitch) {
                                newGlitches.add(verifiedDeal)
                                added = true
                                queueDiscordNotification(verifiedDeal)
                                sendTelegramNotification(verifiedDeal) // #6
                                recordStats(verifiedDeal, matchesWatchList) // #5
                            }

                            if (matchesWatchList) {
                                newWatchlistDeals.add(verifiedDeal)
                                added = true
                                if (!verifiedDeal.isGlitch) recordStats(verifiedDeal, true)
                            }

                            if (added) {
                                processedIds.add(guid)
                                seenDedupKeys.add(verifiedDeal.dedupKey)
                            }
                        } else if (!matchesWatchList) {
                            processedIds.add(guid)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DealFinder", "Fetch Error from ${feed.label}: ${e.message}")
                // #1: Track failure for backoff
                val newFailCount = (feedFailCounts[feed.url] ?: 0) + 1
                feedFailCounts[feed.url] = newFailCount
                val backoffMs = (1 shl newFailCount.coerceAtMost(5)) * 120_000L // 4min, 8min, 16min...
                prefs.edit().putLong("feed_skip_${feed.url.hashCode()}", System.currentTimeMillis() + backoffMs).apply()
            }
        }

        // #3: Persist dedup state
        persistProcessedIds()
        persistDedupKeys()

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
            // #3: Persist deals after update
            persistDeals()
        }
    }

    // ── Feed parsers ──

    private data class FeedItem(val guid: String, val title: String, val link: String)

    // #1: User-Agent rotation
    private fun randomUserAgent(): String = userAgents.random()

    private fun fetchRssFeed(url: String): List<FeedItem> {
        val doc = Jsoup.connect(url)
            .userAgent(randomUserAgent())
            .timeout(10000)
            .get()

        return doc.select("item, entry").map { item ->
            val guid = item.select("guid, id").text().ifBlank {
                item.select("link").attr("href").ifBlank { item.select("link").text() }
            }
            val title = item.select("title").text()
            val link = item.select("link").attr("href").ifBlank { item.select("link").text() }
            FeedItem(guid, title, link)
        }
    }

    // #1: Reddit JSON API (more reliable than RSS, less likely to 429)
    private fun fetchRedditJson(url: String): List<FeedItem> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GlitchDealFinder/1.0 (Android TV deal bot)")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        response.close()

        val json = JSONObject(body)
        val children = json.getJSONObject("data").getJSONArray("children")
        val items = mutableListOf<FeedItem>()

        for (i in 0 until children.length()) {
            val post = children.getJSONObject(i).getJSONObject("data")
            val id = post.getString("id")
            val title = post.getString("title")
            val postUrl = post.optString("url", "")
            val permalink = "https://www.reddit.com" + post.getString("permalink")
            // Use the external URL if available, otherwise the Reddit permalink
            val link = if (postUrl.isNotBlank() && !postUrl.contains("reddit.com")) postUrl else permalink
            items.add(FeedItem(id, title, link))
        }
        return items
    }

    // ── Deal verification (#2: extract actual retailer URL, #6: no fake deals) ──

    private suspend fun verifyDeal(title: String, url: String, id: String): Deal? {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .timeout(8000)
                .followRedirects(true)
                .get()

            val store = extractStore(title)

            // #2: Extract actual product URL from deal aggregator page
            val productUrl = extractProductUrl(doc, url)

            // If we got a real product URL, scrape THAT page for prices
            val priceDoc = if (productUrl.isNotBlank() && productUrl != url) {
                try {
                    Jsoup.connect(productUrl)
                        .userAgent(randomUserAgent())
                        .timeout(8000)
                        .followRedirects(true)
                        .get()
                } catch (e: Exception) {
                    doc // Fall back to the deal page
                }
            } else {
                doc
            }

            val currentPrice = parseCurrentPrice(priceDoc, title)
            var originalPrice = parseHistoricalPrice(priceDoc, currentPrice)
            // CamelCamelCamel fallback for Amazon if we didn't find a historical price
            if (originalPrice <= currentPrice && productUrl.contains("amazon.com", true)) {
                val camelPrice = lookupCamelPrice(productUrl)
                if (camelPrice > currentPrice) originalPrice = camelPrice
            }

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
                url = url,
                productUrl = productUrl,
                verified = true
            )
        } catch (e: Exception) {
            // #6: Don't fabricate fake deals on error — mark as unverified instead
            val hasFreeShippingMention = title.contains("free shipping", true) ||
                    title.contains("free s/h", true) ||
                    title.contains("free s&h", true) ||
                    title.contains("free s ", true)

            val isExplicitGlitch = title.contains("$0.01") || title.contains("penny", true) ||
                    (title.contains("free", true) && !hasFreeShippingMention)

            if (isExplicitGlitch) {
                // Return as unverified — UI will show it differently
                return Deal(
                    id = id, title = title, price = 0.01, originalPrice = 0.0,
                    store = extractStore(title), url = url,
                    verified = false // #6: marked unverified
                )
            }
            null
        }
    }

    // #2: Extract the actual retailer product URL from a deal aggregator page
    private fun extractProductUrl(doc: Document, originalUrl: String): String {
        // Slickdeals: look for the "Go to Deal" link
        doc.select("a.button--primary, a[data-product-url], a.dealCard-dealLink").firstOrNull()?.let {
            val href = it.attr("abs:href")
            if (href.isNotBlank()) return href
        }

        // Generic: look for links to known retailers
        val retailerDomains = listOf("amazon.com", "walmart.com", "bestbuy.com", "target.com",
            "newegg.com", "ebay.com", "homedepot.com", "staples.com", "costco.com",
            "gamestop.com", "bhphotovideo.com", "adorama.com")

        for (link in doc.select("a[href]")) {
            val href = link.attr("abs:href")
            if (retailerDomains.any { href.contains(it, true) }) {
                return href
            }
        }

        return originalUrl
    }

    private fun parseCurrentPrice(doc: Document, title: String): Double {
        val selectors = listOf(
            ".a-offscreen", ".price-characteristic", ".current-price",
            "[itemprop=price]", ".pd-price", ".price", ".offer-price",
            ".priceView-customer-price span", // Best Buy
            "[data-testid=price]", // Walmart
            ".product-price"
        )
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

    // CamelCamelCamel price history for Amazon products
    private fun lookupCamelPrice(productUrl: String): Double {
        if (!productUrl.contains("amazon.com", true)) return 0.0
        try {
            // Extract ASIN from Amazon URL
            val asinMatch = Regex("/(?:dp|gp/product)/([A-Z0-9]{10})").find(productUrl) ?: return 0.0
            val asin = asinMatch.groupValues[1]
            val camelUrl = "https://camelcamelcamel.com/product/$asin"
            val doc = Jsoup.connect(camelUrl)
                .userAgent(randomUserAgent())
                .timeout(8000)
                .get()
            // Look for the highest price (MSRP approximation)
            val highestText = doc.select(".highest_price .green, .stat_val").firstOrNull()?.text() ?: ""
            val price = highestText.replace(Regex("[^\\d.]"), "").toDoubleOrNull()
            if (price != null && price > 0) return price
        } catch (e: Exception) {
            Log.d("DealFinder", "CamelCamelCamel lookup failed: ${e.message}")
        }
        return 0.0
    }

    private fun parseHistoricalPrice(doc: Document, currentPrice: Double): Double {
        val msrpSelectors = listOf(
            ".a-text-price", ".price-strike", ".was-price", ".list-price",
            "[data-a-strike=true]", ".basisPrice", ".regular-price", ".strike", ".msrp",
            ".pricing-information__was-price", // Best Buy
            "[data-testid=list-price]" // Walmart
        )
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
        val stores = listOf("Amazon", "Walmart", "Best Buy", "Staples", "Home Depot",
            "Target", "eBay", "Newegg", "B&H", "Costco", "GameStop", "Adorama",
            "AliExpress", "Micro Center", "Dell", "Lenovo", "HP")
        return stores.firstOrNull { title.contains(it, ignoreCase = true) } ?: "Retailer"
    }

    // ── Discord notifications (#5: queued with rate limiting) ──

    private fun queueDiscordNotification(deal: Deal) {
        viewModelScope.launch {
            discordMutex.withLock {
                discordQueue.add(deal)
            }
        }
    }

    private fun startDiscordQueueProcessor() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(2500) // Process queue every 2.5s (~24/min, under Discord's 30/min limit)
                val deal = discordMutex.withLock {
                    if (discordQueue.isNotEmpty()) discordQueue.removeAt(0) else null
                }
                if (deal != null) {
                    sendDiscordNotification(deal)
                }
            }
        }
    }

    private fun sendDiscordNotification(deal: Deal) {
        val isUnicorn = deal.isUnicorn
        // #8: Use unicorn-specific webhook if configured, otherwise fall back to main
        val url = if (isUnicorn && _unicornWebhookUrl.value.isNotBlank()) _unicornWebhookUrl.value else _webhookUrl.value
        if (url.isBlank()) return

        // #8: If unicorn has its own webhook AND main webhook exists, also send to main
        val alsoSendToMain = isUnicorn && _unicornWebhookUrl.value.isNotBlank() && _webhookUrl.value.isNotBlank()

        try {
            val verifiedTag = if (deal.verified) "VERIFIED" else "UNVERIFIED"
            val priorityTag = if (isUnicorn) "🦄 UNICORN" else "🚨 GLITCH"

            val json = JSONObject()
            json.put("username", if (isUnicorn) "🦄 UNICORN ALERT" else "Glitch Bot")
            json.put("avatar_url", "https://cdn-icons-png.flaticon.com/512/5971/5971593.png")

            // Unicorn deals get @everyone mention to ping all phones
            if (isUnicorn) {
                json.put("content", "**@everyone** 🦄 UNICORN DEAL FOUND — ACT NOW!")
            }

            val embed = JSONObject()
            embed.put("title", "$priorityTag [$verifiedTag]: ${deal.title}")
            embed.put("description", buildString {
                append("Found a deal at **${deal.store}**\n")
                if (isUnicorn) append("⚡ **THIS IS A UNICORN — BUY IMMEDIATELY BEFORE IT'S FIXED** ⚡\n")
                if (!deal.verified) append("⚠️ Price could not be independently verified\n")
            })
            embed.put("url", deal.productUrl.ifBlank { deal.url })
            // Colors: Unicorn = bright gold, Verified = red, Unverified = yellow
            embed.put("color", when {
                isUnicorn -> 16766720 // Gold
                deal.verified -> 15158332 // Red
                else -> 16776960 // Yellow
            })

            val fields = JSONArray()
            fields.put(JSONObject().apply {
                put("name", "💰 Price")
                put("value", if (deal.price <= 0.01) "**PENNY/FREE** 🔥" else "**$${String.format(Locale.US, "%.2f", deal.price)}**")
                put("inline", true)
            })
            fields.put(JSONObject().apply {
                put("name", "📉 Discount")
                put("value", "**${deal.discountPercentage}% OFF**")
                put("inline", true)
            })
            if (deal.originalPrice > 0) {
                fields.put(JSONObject().apply {
                    put("name", "📊 Was")
                    put("value", "$${String.format(Locale.US, "%.2f", deal.originalPrice)}")
                    put("inline", true)
                })
            }

            embed.put("fields", fields)
            embed.put("footer", JSONObject().apply {
                put("text", "Glitch Deal Finder TV • ${if (isUnicorn) "CRITICAL PRIORITY" else "Standard Alert"}")
            })
            embed.put("timestamp", java.time.Instant.now().toString())

            val embeds = JSONArray()
            embeds.put(embed)
            json.put("embeds", embeds)

            val bodyStr = json.toString()
            val body = bodyStr.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().close()

            // #8: Also send to main webhook if unicorn has its own channel
            if (alsoSendToMain) {
                val mainBody = bodyStr.toRequestBody("application/json".toMediaType())
                val mainReq = Request.Builder().url(_webhookUrl.value).post(mainBody).build()
                client.newCall(mainReq).execute().close()
            }

            Log.d("Discord", "Sent ${if (isUnicorn) "UNICORN" else "glitch"} alert: ${deal.title}")
        } catch (e: Exception) {
            Log.e("Discord", "Failed to send notification", e)
        }
    }

    // ── #1: Expiry checker — rescan live glitches to see if they're still active ──

    private fun startExpiryChecker() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(90_000) // Check every 90 seconds
                val liveDeals = _glitchDeals.value.filter { it.status == DealStatus.LIVE && it.verified }
                for (deal in liveDeals.take(5)) { // Check up to 5 per cycle
                    try {
                        val checkUrl = deal.productUrl.ifBlank { deal.url }
                        val doc = Jsoup.connect(checkUrl)
                            .userAgent(randomUserAgent())
                            .timeout(6000)
                            .followRedirects(true)
                            .get()
                        val currentPrice = parseCurrentPrice(doc, deal.title)
                        // If price jumped back up significantly, mark as expired
                        val expired = currentPrice > 0 && deal.price > 0 &&
                                currentPrice > deal.price * 2.0 // Price more than doubled = fixed
                        val updatedDeal = deal.copy(
                            status = if (expired) DealStatus.EXPIRED else DealStatus.LIVE,
                            lastCheckedAt = System.currentTimeMillis()
                        )
                        _glitchDeals.value = _glitchDeals.value.map {
                            if (it.id == deal.id) updatedDeal else it
                        }
                        if (expired) Log.d("DealFinder", "EXPIRED: ${deal.title}")
                    } catch (e: Exception) {
                        // Can't check — mark as unknown
                        _glitchDeals.value = _glitchDeals.value.map {
                            if (it.id == deal.id) it.copy(status = DealStatus.UNKNOWN, lastCheckedAt = System.currentTimeMillis()) else it
                        }
                    }
                }
                persistDeals()
            }
        }
    }

    // ── #3: Twitter/X scanner for real-time glitch mentions ──

    private fun fetchTwitterDeals(): List<FeedItem> {
        // Use Twitter's syndication/search (no API key needed, public search)
        val queries = listOf("pricing+error", "price+glitch", "penny+deal", "walmart+glitch", "amazon+glitch")
        val items = mutableListOf<FeedItem>()
        for (query in queries) {
            try {
                val searchUrl = "https://nitter.net/search?f=tweets&q=$query&since=&until=&near="
                val doc = Jsoup.connect(searchUrl)
                    .userAgent(randomUserAgent())
                    .timeout(8000)
                    .get()
                val tweets = doc.select(".timeline-item")
                for (tweet in tweets.take(5)) {
                    val text = tweet.select(".tweet-content").text()
                    val tweetLink = tweet.select(".tweet-link").attr("href")
                    val id = tweetLink.substringAfterLast("/").ifBlank { text.hashCode().toString() }
                    // Extract URLs from tweet text
                    val urlMatch = Regex("https?://\\S+").find(text)
                    val link = urlMatch?.value ?: "https://nitter.net$tweetLink"
                    if (text.isNotBlank()) items.add(FeedItem("tw_$id", text.take(200), link))
                }
            } catch (e: Exception) {
                Log.d("DealFinder", "Twitter scan failed for $query: ${e.message}")
            }
        }
        return items
    }

    // ── #10: BrickSeek in-store clearance scanner ──

    private fun fetchBrickSeekDeals(): List<FeedItem> {
        val zip = _zipCode.value
        if (zip.isBlank() || zip.length < 5) return emptyList()
        val items = mutableListOf<FeedItem>()
        try {
            // BrickSeek Walmart clearance feed
            val url = "https://brickseek.com/walmart-clearance-finder/?zip=$zip"
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .timeout(10000)
                .get()
            val deals = doc.select(".item-list__item")
            for (item in deals.take(10)) {
                val title = item.select(".item-list__title").text()
                val priceText = item.select(".item-list__price").text()
                val link = item.select("a").attr("abs:href")
                val id = link.hashCode().toString()
                if (title.isNotBlank()) {
                    items.add(FeedItem("bs_$id", "$title — $priceText (In-Store)", link))
                }
            }
        } catch (e: Exception) {
            Log.d("DealFinder", "BrickSeek scan failed: ${e.message}")
        }
        return items
    }

    // ── #6: Telegram notifications ──

    private fun sendTelegramNotification(deal: Deal) {
        val token = _telegramBotToken.value
        val chatId = _telegramChatId.value
        if (token.isBlank() || chatId.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isUnicorn = deal.isUnicorn
                val priceStr = if (deal.price <= 0.01) "PENNY/FREE" else "$${String.format(Locale.US, "%.2f", deal.price)}"
                val emoji = if (isUnicorn) "🦄" else "🚨"
                val priority = if (isUnicorn) "UNICORN" else "GLITCH"
                val savings = if (deal.valueSavings > 0) " (save ${deal.valueSavingsFormatted})" else ""
                val link = deal.productUrl.ifBlank { deal.url }
                val cartLink = deal.cartUrl

                val text = buildString {
                    append("$emoji *$priority ALERT* $emoji\n\n")
                    append("*${deal.title}*\n")
                    append("💰 $priceStr at ${deal.store}$savings\n")
                    append("📉 ${deal.discountPercentage}% OFF\n\n")
                    append("🔗 [View Deal]($link)\n")
                    if (cartLink != link) append("🛒 [Add to Cart]($cartLink)\n")
                    if (isUnicorn) append("\n⚡ *ACT NOW — WILL BE FIXED SOON* ⚡")
                }

                val msgUrl = "https://api.telegram.org/bot$token/sendMessage"
                val json = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "Markdown")
                    put("disable_web_page_preview", false)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(msgUrl).post(body).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("Telegram", "Failed to send notification", e)
            }
        }
    }

    // ── Public actions ──

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
        persistRemovedIds()
        _glitchDeals.value = _glitchDeals.value.filter { it.id != deal.id }
        _watchlistDeals.value = _watchlistDeals.value.filter { it.id != deal.id }
        persistDeals()
    }

    fun clearLastDeal() {
        _lastFoundDeal.value = null
    }

    fun getShareText(deal: Deal): String {
        val priceStr = if (deal.price <= 0.01) "FREE/PENNY" else "$${String.format(Locale.US, "%.2f", deal.price)}"
        val emoji = if (deal.isUnicorn) "🦄" else "🚨"
        val savings = if (deal.valueSavings > 0) " — save ${deal.valueSavingsFormatted}" else ""
        val link = deal.productUrl.ifBlank { deal.url }
        val cart = deal.cartUrl
        return buildString {
            append("$emoji Deal Alert: ${deal.title}\n")
            append("$priceStr at ${deal.store} (${deal.discountPercentage}% off$savings)\n")
            append("🔗 $link\n")
            if (cart != link) append("🛒 Add to cart: $cart")
        }
    }

    // #9: Voice search — add keyword from voice input
    fun addKeywordFromVoice(text: String) {
        val cleaned = text.trim()
        if (cleaned.isNotBlank()) addKeyword(cleaned)
    }
}
