package com.example.glitchdealfinder

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import com.example.glitchdealfinder.ui.theme.GlitchDealFinderTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlitchDealFinderTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    val viewModel: DealViewModel = viewModel()
                    val warRoom by viewModel.warRoomMode.collectAsState()
                    if (warRoom) WarRoomScreen(viewModel) else MainScreen(viewModel)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// #3: AMBIENT BACKGROUND — animated gradient mesh
// ═══════════════════════════════════════════
@Composable
fun AmbientBackground() {
    val transition = rememberInfiniteTransition(label = "ambient")
    val offset by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing)),
        label = "ambientShift"
    )
    Box(
        modifier = Modifier.fillMaxSize().drawBehind {
            val cx = size.width / 2 + cos(Math.toRadians(offset.toDouble())).toFloat() * size.width * 0.3f
            val cy = size.height / 2 + sin(Math.toRadians(offset.toDouble() * 0.7)).toFloat() * size.height * 0.3f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x1500FFCC), Color(0x08004433), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.maxDimension * 0.5f
                ),
                radius = size.maxDimension,
                center = Offset(cx, cy)
            )
            val cx2 = size.width * 0.7f + cos(Math.toRadians(offset.toDouble() * 1.3 + 180)).toFloat() * size.width * 0.2f
            val cy2 = size.height * 0.3f + sin(Math.toRadians(offset.toDouble() * 0.5 + 90)).toFloat() * size.height * 0.2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x10FF3333), Color(0x06330000), Color.Transparent),
                    center = Offset(cx2, cy2),
                    radius = size.maxDimension * 0.4f
                ),
                radius = size.maxDimension * 0.8f,
                center = Offset(cx2, cy2)
            )
        }
    )
}

// ═══════════════════════════════════════════
// #1: RADAR SCAN ANIMATION
// ═══════════════════════════════════════════
@Composable
fun RadarScanIndicator(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "sweep"
    )
    val radarGreen = Color(0xFF00FF66)
    val radarDim = Color(0xFF003311)

    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        // Outer ring
        Canvas(modifier = Modifier.size(48.dp)) {
            drawCircle(color = radarDim, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            drawCircle(color = radarDim, radius = size.minDimension / 3)
            drawCircle(color = radarDim, radius = size.minDimension / 6)
            // Sweep line
            if (active) {
                rotate(sweep) {
                    drawLine(
                        color = radarGreen,
                        start = center,
                        end = Offset(center.x, center.y - size.minDimension / 2),
                        strokeWidth = 2f
                    )
                }
                // Fading trail
                for (i in 1..8) {
                    val trailAngle = sweep - i * 5f
                    val alpha = 1f - (i / 8f)
                    rotate(trailAngle) {
                        drawLine(
                            color = radarGreen.copy(alpha = alpha * 0.3f),
                            start = center,
                            end = Offset(center.x, center.y - size.minDimension / 2),
                            strokeWidth = 1f
                        )
                    }
                }
            }
            // Center dot
            drawCircle(color = if (active) radarGreen else Color.Red, radius = 3f)
        }
    }
}

// ═══════════════════════════════════════════
// #2: SCROLLING DEAL TICKER
// ═══════════════════════════════════════════
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DealTicker(deals: List<Deal>) {
    if (deals.isEmpty()) return
    val tickerItems = deals.take(20)
    // Auto-scroll offset
    val scrollState = rememberScrollState()
    LaunchedEffect(tickerItems) {
        while (true) {
            scrollState.animateScrollTo(scrollState.maxValue, tween(tickerItems.size * 3000, easing = LinearEasing))
            scrollState.scrollTo(0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .border(BorderStroke(1.dp, Color(0xFF1A1A1A)))
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            for (deal in tickerItems) {
                val emoji = when {
                    deal.isUnicorn -> "🦄"
                    deal.status == DealStatus.EXPIRED -> "☠"
                    deal.isGlitch -> "🚨"
                    else -> "📦"
                }
                val priceStr = if (deal.price <= 0.01) "FREE" else "$${String.format(Locale.US, "%.2f", deal.price)}"
                Text(
                    text = "$emoji ${deal.title.take(30)} — $priceStr at ${deal.store} (${deal.discountPercentage}% OFF)",
                    color = when {
                        deal.isUnicorn -> Color(0xFFFFD700)
                        deal.status == DealStatus.EXPIRED -> Color.DarkGray
                        else -> Color(0xFF00FFCC)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

// ═══════════════════════════════════════════
// #10: FEED HEALTH BAR
// ═══════════════════════════════════════════
@Composable
fun FeedHealthBar(feeds: List<DealViewModel.FeedHealth>) {
    if (feeds.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (feed in feeds) {
            val dot = when (feed.status) {
                "ok" -> Color(0xFF00FF00)
                "warn" -> Color(0xFFFF9800)
                else -> Color.Red
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dot))
                Spacer(modifier = Modifier.width(3.dp))
                Text(feed.label.take(12), color = Color.DarkGray, fontSize = 9.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════
// MAIN SCREEN
// ═══════════════════════════════════════════
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainScreen(viewModel: DealViewModel) {
    val glitchDeals by viewModel.glitchDeals.collectAsState()
    val watchlistDeals by viewModel.watchlistDeals.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val keywords by viewModel.searchKeywords.collectAsState()
    val lastDeal by viewModel.lastFoundDeal.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val feedHealth by viewModel.feedHealth.collectAsState()
    val activeCategory by viewModel.activeCategory.collectAsState()

    var selectedDeal by remember { mutableStateOf<Deal?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(lastDeal) {
        if (lastDeal != null) playAlertSound(isGlitch = lastDeal!!.isGlitch, isUnicorn = lastDeal!!.isUnicorn)
    }

    // #9: D-Pad shortcuts
    Box(modifier = Modifier
        .fillMaxSize()
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                when (event.key) {
                    Key.MediaPlayPause -> { viewModel.toggleWarRoom(); true }
                    Key.Info -> { showStats = !showStats; true }
                    else -> false
                }
            } else false
        }
    ) {
        // #3: Ambient background
        AmbientBackground()

        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            // Header row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // #1: Radar instead of heartbeat
                    RadarScanIndicator(active = isSearching)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("GLITCH DEAL BOT", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF00FFCC))
                        Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // #5: Total saved badge
                    val totalSaved = viewModel.getTotalSaved()
                    if (totalSaved > 0) {
                        Text(
                            "💰 SAVED $${String.format(Locale.US, "%.0f", totalSaved)}",
                            color = Color(0xFF00FF88), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Color(0xFF002200), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Button(onClick = { showStats = !showStats }) { Text("📊") }
                    Button(onClick = { showHistory = !showHistory }) { Text("📜") }
                    Button(onClick = { viewModel.toggleWarRoom() }) { Text("🖥") }
                    Button(onClick = { showSettingsDialog = true }) { Text("⚙️") }
                }
            }

            // #10: Feed health
            FeedHealthBar(feedHealth)

            // #5: Stats bar (collapsible)
            AnimatedVisibility(showStats) {
                StatsBar(stats, viewModel)
            }

            // #6: Category filter pills
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(viewModel.CATEGORIES) { cat ->
                    val isActive = cat == activeCategory
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isActive) Color(0xFF00FFCC) else Color(0xFF1A1A1A))
                            .clickable { viewModel.setCategory(cat) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(cat, color = if (isActive) Color.Black else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main content
            Row(modifier = Modifier.weight(1f)) {
                KeywordPanel(
                    keywords = keywords,
                    onRemove = { viewModel.removeKeyword(it) },
                    onShowAdd = { showAddDialog = true },
                    onVoiceResult = { viewModel.addKeywordFromVoice(it) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(24.dp))

                // Filter by category
                val filteredGlitches = if (activeCategory == "All") glitchDeals else glitchDeals.filter { viewModel.categorize(it) == activeCategory }
                val filteredWatchlist = if (activeCategory == "All") watchlistDeals else watchlistDeals.filter { viewModel.categorize(it) == activeCategory }

                DualDealHistoryPanel(
                    glitchDeals = filteredGlitches,
                    watchlistDeals = filteredWatchlist,
                    onDealClick = { viewModel.recordDealClick(it); selectedDeal = it },
                    modifier = Modifier.weight(2.5f)
                )
            }

            // #2: Ticker at bottom
            DealTicker(glitchDeals + watchlistDeals)
        }

        // Popups
        val dealToShow = lastDeal ?: selectedDeal
        dealToShow?.let { deal ->
            DealDetailPopup(deal = deal, viewModel = viewModel, isAlert = lastDeal != null,
                onDismiss = { viewModel.clearLastDeal(); selectedDeal = null },
                onRemove = { viewModel.removeDeal(deal); viewModel.clearLastDeal(); selectedDeal = null }
            )
        }
        if (showAddDialog) AddKeywordDialog(onDismiss = { showAddDialog = false }, onAdd = { viewModel.addKeyword(it); showAddDialog = false })
        if (showSettingsDialog) SettingsDialog(viewModel = viewModel, onDismiss = { showSettingsDialog = false })
        if (showHistory) DealHistoryDialog(viewModel = viewModel, onDismiss = { showHistory = false })
    }
}

// ═══════════════════════════════════════════
// #4: WAR ROOM MODE — lean-back, huge text
// ═══════════════════════════════════════════
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WarRoomScreen(viewModel: DealViewModel) {
    val glitchDeals by viewModel.glitchDeals.collectAsState()
    val watchlistDeals by viewModel.watchlistDeals.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Box(modifier = Modifier
        .fillMaxSize()
        .onKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && (e.key == Key.MediaPlayPause || e.key == Key.Back)) { viewModel.toggleWarRoom(); true } else false
        }
    ) {
        AmbientBackground()
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            // Huge header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadarScanIndicator(active = isSearching, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("GLITCH DEAL BOT", fontSize = 36.sp, color = Color(0xFF00FFCC), fontWeight = FontWeight.Black)
                    Text(statusMessage, fontSize = 16.sp, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("💰 SAVED $${String.format(Locale.US, "%.0f", viewModel.getTotalSaved())}", fontSize = 20.sp, color = Color(0xFF00FF88), fontWeight = FontWeight.Bold)
                    Text("${stats.totalGlitchesFound} glitches found", fontSize = 14.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Big deal list
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                val allDeals = (glitchDeals + watchlistDeals).sortedByDescending { it.timestamp }.take(30)
                items(allDeals) { deal ->
                    val emoji = when {
                        deal.status == DealStatus.EXPIRED -> "☠"
                        deal.isUnicorn -> "🦄"
                        deal.isGlitch -> "🚨"
                        else -> "📦"
                    }
                    val priceStr = if (deal.price <= 0.01) "FREE" else "$${String.format(Locale.US, "%.2f", deal.price)}"
                    val color = when {
                        deal.status == DealStatus.EXPIRED -> Color.DarkGray
                        deal.isUnicorn -> Color(0xFFFFD700)
                        else -> Color.White
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(emoji, fontSize = 28.sp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(deal.title, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${deal.store} • ${dateFormat.format(Date(deal.timestamp))}", color = Color.Gray, fontSize = 14.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(priceStr, color = Color.Green, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text("${deal.discountPercentage}% OFF", color = Color(0xFFFFCC00), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (deal.valueSavings > 0) Text("SAVE ${deal.valueSavingsFormatted}", color = Color(0xFF00FF88), fontSize = 12.sp)
                        }
                    }
                }
            }

            // Ticker
            DealTicker(glitchDeals)
        }
    }
}

// ═══════════════════════════════════════════
// #8: DEAL HISTORY DIALOG
// ═══════════════════════════════════════════
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DealHistoryDialog(viewModel: DealViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    val context = LocalContext.current
    val filtered = remember(query, category) { viewModel.getFilteredHistory(query, category) }
    val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
        Surface(
            onClick = {}, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("DEAL HISTORY", color = Color.Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val csv = viewModel.exportDealsCSV()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Deals CSV", csv))
                            Toast.makeText(context, "CSV copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }) { Text("📋 EXPORT") }
                        Button(onClick = onDismiss) { Text("CLOSE") }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Search + filter
                Box(modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    BasicTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color(0xFF00FFCC)),
                        singleLine = true,
                        decorationBox = { inner -> if (query.isEmpty()) Text("Search deals...", color = Color.DarkGray, fontSize = 14.sp); inner() }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("${filtered.size} deals", color = Color.Gray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered) { deal ->
                        val priceStr = if (deal.price <= 0.01) "FREE" else "$${String.format(Locale.US, "%.2f", deal.price)}"
                        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF111111), RoundedCornerShape(8.dp)).padding(10.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(deal.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${deal.store} • ${dateFormat.format(Date(deal.timestamp))} • ${deal.status}", color = Color.Gray, fontSize = 11.sp)
                            }
                            Text("$priceStr  ${deal.discountPercentage}%", color = Color(0xFFFFCC00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// STATS BAR (enhanced with success tracking)
// ═══════════════════════════════════════════
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatsBar(stats: DealViewModel.DealStats, viewModel: DealViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1A0A), RoundedCornerShape(12.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem("🚨", "Glitches", stats.totalGlitchesFound.toString())
        StatItem("🦄", "Unicorns", stats.totalUnicorns.toString())
        StatItem("👀", "Watchlist", stats.totalWatchlistHits.toString())
        StatItem("📅", "Today", stats.dealsToday.toString())
        StatItem("🛒", "Bought", viewModel.getTotalPurchased().toString())
        StatItem("💰", "Saved", if (viewModel.getTotalSaved() > 0) "$${String.format(Locale.US, "%.0f", viewModel.getTotalSaved())}" else "-")
    }
}

@Composable
fun StatItem(emoji: String, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 18.sp)
        Text(value, color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = Color.Gray, fontSize = 9.sp)
    }
}

// ═══════════════════════════════════════════
// HEADER, KEYWORD PANEL, DEAL PANELS (kept from before, with minor updates)
// ═══════════════════════════════════════════

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KeywordPanel(keywords: Set<String>, onRemove: (String) -> Unit, onShowAdd: () -> Unit, onVoiceResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onVoiceResult(it) }
        }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("WATCH LIST", style = MaterialTheme.typography.titleMedium, color = Color.Yellow)
            Text("${keywords.size}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(keywords.toList()) { keyword ->
                Surface(onClick = { onRemove(keyword) }, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(keyword); Text("X", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShowAdd, modifier = Modifier.weight(1f)) { Text("+ ADD") }
            Button(onClick = {
                try {
                    voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an item to watch for...")
                    })
                } catch (_: Exception) {}
            }, colors = ButtonDefaults.colors(containerColor = Color(0xFF003300))) { Text("🎤") }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DualDealHistoryPanel(glitchDeals: List<Deal>, watchlistDeals: List<Deal>, onDealClick: (Deal) -> Unit, modifier: Modifier = Modifier) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Column(modifier = modifier.fillMaxHeight()) {
        Text("ULTIMATE GLITCHES (85%+ OFF)", style = MaterialTheme.typography.titleMedium, color = Color.Red)
        Spacer(modifier = Modifier.height(6.dp))
        Box(modifier = Modifier.weight(1f)) { DealList(deals = glitchDeals, emptyMessage = "Waiting for unicorns...", onDealClick = onDealClick, dateFormat = dateFormat) }
        Spacer(modifier = Modifier.height(16.dp))
        Text("WATCHLIST FINDS", style = MaterialTheme.typography.titleMedium, color = Color.Cyan)
        Spacer(modifier = Modifier.height(6.dp))
        Box(modifier = Modifier.weight(1f)) { DealList(deals = watchlistDeals, emptyMessage = "No matches yet...", onDealClick = onDealClick, dateFormat = dateFormat) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DealList(deals: List<Deal>, emptyMessage: String, onDealClick: (Deal) -> Unit, dateFormat: SimpleDateFormat) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        if (deals.isEmpty()) { item { Text(emptyMessage, color = Color.DarkGray, modifier = Modifier.padding(16.dp)) } }
        items(deals) { deal ->
            val borderColor = when {
                deal.isUnicorn -> Color(0xFFFFD700)
                !deal.verified -> Color(0xFFFF9800)
                else -> Color.Transparent
            }
            val bgColor = when {
                deal.status == DealStatus.EXPIRED -> Color(0xFF0A0A0A)
                deal.isUnicorn -> Color(0xFF1A1500)
                !deal.verified -> Color(0xFF1A1500)
                else -> Color(0xFF1A1A1A)
            }
            val textAlpha = if (deal.status == DealStatus.EXPIRED) 0.4f else 1f

            Surface(
                onClick = { onDealClick(deal) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = bgColor),
                modifier = Modifier.fillMaxWidth().alpha(textAlpha).then(if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp)) else Modifier)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(deal.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (deal.isUnicorn) { Spacer(Modifier.width(6.dp)); Text("🦄", fontSize = 12.sp) }
                            else if (!deal.verified) { Spacer(Modifier.width(6.dp)); Text("⚠", color = Color(0xFFFF9800), fontSize = 10.sp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${deal.store} • ${dateFormat.format(Date(deal.timestamp))}", color = Color.Gray, fontSize = 11.sp)
                            when (deal.status) {
                                DealStatus.EXPIRED -> { Spacer(Modifier.width(4.dp)); Text("☠", fontSize = 10.sp) }
                                DealStatus.LIVE -> if (deal.minutesAlive in 1..15) { Spacer(Modifier.width(4.dp)); Text("⏱${deal.minutesAlive}m", color = Color(0xFF00FF00), fontSize = 9.sp) }
                                else -> {}
                            }
                            if (deal.inStore) { Spacer(Modifier.width(4.dp)); Text("🏪", fontSize = 9.sp) }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${deal.discountPercentage}%", color = Color(0xFFFFCC00), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text(if (deal.price <= 0.01) "FREE" else "$${String.format(Locale.US, "%.2f", deal.price)}", color = Color.Green, fontSize = 12.sp)
                        if (deal.valueSavings > 0) Text(deal.valueSavingsFormatted, color = Color(0xFF00FF88), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// DEAL DETAIL POPUP (with Buy Now, Share, Mark Purchased)
// ═══════════════════════════════════════════
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DealDetailPopup(deal: Deal, viewModel: DealViewModel, isAlert: Boolean, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onDismiss,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.width(580.dp)
        ) {
            Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Header
                when {
                    isAlert && deal.isUnicorn -> {
                        Text("🦄 UNICORN FOUND! 🦄", color = Color(0xFFFFD700), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("BUY NOW — THIS WILL BE FIXED!", color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    isAlert -> Text("🚨 GLITCH DETECTED! 🚨", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    else -> Text("DEAL DETAILS", color = Color.Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (!deal.verified) { Spacer(Modifier.height(4.dp)); Text("⚠ PRICE UNVERIFIED", color = Color(0xFFFF9800), fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                Spacer(modifier = Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deal.title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (deal.price <= 0.01) "FREE / PENNY" else "$${String.format(Locale.US, "%.2f", deal.price)}",
                            fontSize = 28.sp, color = Color.Green, fontWeight = FontWeight.Bold
                        )
                        Row {
                            if (deal.originalPrice > 0) Text("was $${String.format(Locale.US, "%.2f", deal.originalPrice)}", color = Color.Gray, fontSize = 13.sp)
                            if (deal.valueSavings > 0) { Spacer(Modifier.width(10.dp)); Text("SAVE ${deal.valueSavingsFormatted}", color = Color(0xFF00FF88), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                        Text("at ${deal.store}", color = Color.LightGray, fontSize = 13.sp)
                        // Countdown
                        if (deal.status == DealStatus.LIVE && deal.minutesAlive > 0) Text("⏱ Live ${deal.minutesAlive}m — avg glitch lasts ~8 min", color = Color(0xFF00FF00), fontSize = 11.sp)
                        else if (deal.status == DealStatus.EXPIRED) Text("☠ This deal has been fixed", color = Color.Red, fontSize = 11.sp)
                        if (deal.inStore) Text("🏪 In-store — ZIP: ${deal.zipCode}", color = Color(0xFF00BFFF), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncQRCodeImage(url = deal.cartUrl)
                        Text("Scan to Buy", color = Color.White, fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons — 2 rows for more space
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deal.status != DealStatus.EXPIRED) {
                        Button(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Cart", deal.cartUrl))
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deal.cartUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (_: Exception) { Toast.makeText(context, "Cart link copied!", Toast.LENGTH_SHORT).show() }
                        }, colors = ButtonDefaults.colors(containerColor = Color(0xFF006600))) { Text("🛒 BUY NOW") }
                    }
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Deal", viewModel.getShareText(deal)))
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, viewModel.getShareText(deal)); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }, "Share")) }
                        catch (_: Exception) { Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show() }
                    }, colors = ButtonDefaults.colors(containerColor = Color(0xFF003344))) { Text("📤 SHARE") }
                    // #5: Mark as purchased
                    Button(onClick = {
                        viewModel.markPurchased(deal)
                        Toast.makeText(context, "Marked as purchased! +${deal.valueSavingsFormatted} saved", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }, colors = ButtonDefaults.colors(containerColor = Color(0xFF333300))) { Text("✅ BOUGHT") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRemove, colors = ButtonDefaults.colors(containerColor = Color(0xFF330000))) { Text("🗑 REMOVE") }
                    Button(onClick = onDismiss, modifier = Modifier.focusRequester(focusRequester)) { Text("CLOSE") }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// QR CODE (async), ADD DIALOG, SETTINGS
// ═══════════════════════════════════════════
@Composable
fun AsyncQRCodeImage(url: String) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(url) {
        loading = true
        bitmap = withContext(Dispatchers.Default) {
            try {
                val size = 512; val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
                Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply { for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
            } catch (_: Exception) { null }
        }
        loading = false
    }
    Box(modifier = Modifier.size(130.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp), contentAlignment = Alignment.Center) {
        if (loading || bitmap == null) Text("...", color = Color.Gray, fontSize = 12.sp)
        else Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddKeywordDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Surface(onClick = {}, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)), colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.width(600.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ADD SEARCH ITEM", style = MaterialTheme.typography.headlineSmall, color = Color.Yellow)
                Spacer(Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(16.dp)) {
                    BasicTextField(
                        value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 24.sp), cursorBrush = SolidColor(Color(0xFF00FFCC)), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                        decorationBox = { inner -> if (text.isEmpty()) Text("Enter Item Name...", color = Color.DarkGray, fontSize = 24.sp); inner() }
                    )
                }
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { if (text.isNotBlank()) onAdd(text) }) { Text("ADD") }
                    Button(onClick = onDismiss) { Text("CANCEL") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsDialog(viewModel: DealViewModel, onDismiss: () -> Unit) {
    val webhook by viewModel.webhookUrl.collectAsState(); val unicornWh by viewModel.unicornWebhookUrl.collectAsState()
    val tgToken by viewModel.telegramBotToken.collectAsState(); val tgChat by viewModel.telegramChatId.collectAsState()
    val zip by viewModel.zipCode.collectAsState()
    var dUrl by remember { mutableStateOf(webhook) }; var uUrl by remember { mutableStateOf(unicornWh) }
    var tt by remember { mutableStateOf(tgToken) }; var tc by remember { mutableStateOf(tgChat) }; var zc by remember { mutableStateOf(zip) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Surface(onClick = {}, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)), colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.width(700.dp)) {
            LazyColumn(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item { Text("SETTINGS", style = MaterialTheme.typography.headlineSmall, color = Color.Cyan); Spacer(Modifier.height(20.dp)) }
                item { SettingsField("Discord Webhook (all deals)", dUrl, fr) { dUrl = it } }
                item { SettingsField("Unicorn-Only Webhook", uUrl) { uUrl = it } }
                item { SettingsField("Telegram Bot Token", tt) { tt = it } }
                item { SettingsField("Telegram Chat ID", tc) { tc = it } }
                item { SettingsField("ZIP Code (in-store deals)", zc) { zc = it } }
                item {
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { viewModel.updateWebhook(dUrl); viewModel.updateUnicornWebhook(uUrl); viewModel.updateTelegram(tt, tc); viewModel.updateZipCode(zc); onDismiss() }) { Text("SAVE") }
                        Button(onClick = onDismiss) { Text("CANCEL") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsField(label: String, value: String, focusReq: FocusRequester? = null, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(label, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(10.dp)) {
            BasicTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().then(if (focusReq != null) Modifier.focusRequester(focusReq) else Modifier),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = SolidColor(Color(0xFF00FFCC)), singleLine = true,
                decorationBox = { inner -> if (value.isEmpty()) Text("Enter...", color = Color.DarkGray, fontSize = 13.sp); inner() }
            )
        }
    }
}

fun playAlertSound(isGlitch: Boolean, isUnicorn: Boolean = false) {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        when {
            isUnicorn -> toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 300)
            isGlitch -> toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            else -> toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
        }
    } catch (_: Exception) {}
}
