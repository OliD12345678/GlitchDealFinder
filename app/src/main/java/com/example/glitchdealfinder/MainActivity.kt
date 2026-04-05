package com.example.glitchdealfinder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import com.example.glitchdealfinder.ui.theme.GlitchDealFinderTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlitchDealFinderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    val viewModel: DealViewModel = viewModel()
                    MainScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainScreen(viewModel: DealViewModel) {
    val glitchDeals by viewModel.glitchDeals.collectAsState()
    val watchlistDeals by viewModel.watchlistDeals.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val keywords by viewModel.searchKeywords.collectAsState()
    val lastDeal by viewModel.lastFoundDeal.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val webhookUrl by viewModel.webhookUrl.collectAsState()

    var selectedDeal by remember { mutableStateOf<Deal?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Play alert sound on new deal — unicorns get the most urgent sound
    LaunchedEffect(lastDeal) {
        if (lastDeal != null) {
            playAlertSound(isGlitch = lastDeal!!.isGlitch, isUnicorn = lastDeal!!.isUnicorn)
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Header(isSearching, statusMessage)
                Button(onClick = { showSettingsDialog = true }) {
                    Text("⚙️ NOTIFICATIONS")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                KeywordPanel(
                    keywords = keywords,
                    onRemove = { viewModel.removeKeyword(it) },
                    onShowAdd = { showAddDialog = true },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(32.dp))

                DualDealHistoryPanel(
                    glitchDeals = glitchDeals,
                    watchlistDeals = watchlistDeals,
                    onDealClick = { selectedDeal = it },
                    modifier = Modifier.weight(2.5f)
                )
            }
        }

        val dealToShow = lastDeal ?: selectedDeal
        dealToShow?.let { deal ->
            DealDetailPopup(
                deal = deal,
                viewModel = viewModel,
                isAlert = lastDeal != null,
                onDismiss = {
                    viewModel.clearLastDeal()
                    selectedDeal = null
                },
                onRemove = {
                    viewModel.removeDeal(deal)
                    viewModel.clearLastDeal()
                    selectedDeal = null
                }
            )
        }

        if (showAddDialog) {
            AddKeywordDialog(
                onDismiss = { showAddDialog = false },
                onAdd = {
                    viewModel.addKeyword(it)
                    showAddDialog = false
                }
            )
        }

        if (showSettingsDialog) {
            WebhookSettingsDialog(
                currentUrl = webhookUrl,
                onDismiss = { showSettingsDialog = false },
                onSave = {
                    viewModel.updateWebhook(it)
                    showSettingsDialog = false
                }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Header(isSearching: Boolean, status: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeartbeatIndicator(active = isSearching)
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = "GLITCH DEAL BOT",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF00FFCC)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )
        }
    }
}

@Composable
fun HeartbeatIndicator(active: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .scale(if (active) scale else 1f)
                .clip(CircleShape)
                .background(if (active) Color(0xFF00FF00) else Color.Red)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KeywordPanel(
    keywords: Set<String>,
    onRemove: (String) -> Unit,
    onShowAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("WATCH LIST", style = MaterialTheme.typography.titleMedium, color = Color.Yellow)
            Text("${keywords.size} items", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(keywords.toList()) { keyword ->
                Surface(
                    onClick = { onRemove(keyword) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(keyword)
                        Text("X", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onShowAdd, modifier = Modifier.fillMaxWidth()) {
            Text("+ ADD ITEM / VOICE")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DualDealHistoryPanel(
    glitchDeals: List<Deal>,
    watchlistDeals: List<Deal>,
    onDealClick: (Deal) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(modifier = modifier.fillMaxHeight()) {
        Text("ULTIMATE GLITCHES (85%+ OFF)", style = MaterialTheme.typography.titleMedium, color = Color.Red)
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            DealList(deals = glitchDeals, emptyMessage = "Waiting for unicorns...", onDealClick = onDealClick, dateFormat = dateFormat)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("WATCHLIST FINDS", style = MaterialTheme.typography.titleMedium, color = Color.Cyan)
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            DealList(deals = watchlistDeals, emptyMessage = "No matches yet...", onDealClick = onDealClick, dateFormat = dateFormat)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DealList(
    deals: List<Deal>,
    emptyMessage: String,
    onDealClick: (Deal) -> Unit,
    dateFormat: SimpleDateFormat
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        if (deals.isEmpty()) {
            item {
                Text(emptyMessage, color = Color.DarkGray, modifier = Modifier.padding(16.dp))
            }
        }
        items(deals) { deal ->
            // Visual distinction: unicorn > glitch > unverified
            val borderColor = when {
                deal.isUnicorn -> Color(0xFFFFD700) // Gold border for unicorns
                !deal.verified -> Color(0xFFFF9800) // Orange for unverified
                else -> Color.Transparent
            }
            val bgColor = when {
                deal.isUnicorn -> Color(0xFF1A1500) // Warm glow background
                !deal.verified -> Color(0xFF1A1500)
                else -> Color(0xFF1A1A1A)
            }

            Surface(
                onClick = { onDealClick(deal) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = bgColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp)) else Modifier)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(deal.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (deal.isUnicorn) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("🦄 UNICORN", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            } else if (!deal.verified) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("⚠ UNVERIFIED", color = Color(0xFFFF9800), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text("${deal.store} • ${dateFormat.format(Date(deal.timestamp))}", color = Color.Gray, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${deal.discountPercentage}% OFF",
                            color = Color(0xFFFFCC00),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                        val priceString = if (deal.price <= 0.01) "PENNY!" else String.format(Locale.US, "$%.2f", deal.price)
                        Text(
                            text = priceString,
                            color = Color.Green,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DealDetailPopup(deal: Deal, viewModel: DealViewModel, isAlert: Boolean, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onDismiss,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.width(550.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isAlert && deal.isUnicorn) {
                    Text("🦄 UNICORN FOUND! 🦄", color = Color(0xFFFFD700), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("BUY NOW — THIS WILL BE FIXED!", color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                } else if (isAlert) {
                    Text("🚨 GLITCH DETECTED! 🚨", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                } else {
                    Text("DEAL DETAILS", color = Color.Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }

                // #6: Unverified warning
                if (!deal.verified) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "⚠ PRICE UNVERIFIED — could not scrape retailer page",
                        color = Color(0xFFFF9800),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deal.title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        val priceString = if (deal.price <= 0.01) "FREE / PENNY DEAL" else String.format(Locale.US, "$%.2f", deal.price)
                        Text(
                            text = priceString,
                            fontSize = 28.sp,
                            color = Color.Green,
                            fontWeight = FontWeight.Bold
                        )
                        if (deal.originalPrice > 0) {
                            Text("was $${String.format(Locale.US, "%.2f", deal.originalPrice)}", color = Color.Gray, fontSize = 14.sp)
                        }
                        Text("at ${deal.store}", color = Color.LightGray)
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // #7: Async QR code generation
                        AsyncQRCodeImage(url = deal.productUrl.ifBlank { deal.url })
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Scan to Buy", color = Color.White, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // #10: Share button
                    Button(
                        onClick = {
                            val shareText = viewModel.getShareText(deal)
                            // Copy to clipboard (works on TV)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Deal", shareText))
                            // Also try share intent (works if phone-like device)
                            try {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Deal"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.colors(containerColor = Color(0xFF004400))
                    ) {
                        Text("📤 SHARE")
                    }
                    Button(
                        onClick = onRemove,
                        colors = ButtonDefaults.colors(containerColor = Color(0xFF440000))
                    ) {
                        Text("🗑 REMOVE")
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(focusRequester)
                    ) {
                        Text("CLOSE")
                    }
                }
            }
        }
    }
}

// #7: QR code generated on background thread with loading state
@Composable
fun AsyncQRCodeImage(url: String) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(url) {
        loading = true
        bitmap = withContext(Dispatchers.Default) {
            try {
                val size = 512
                val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
                Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                    for (x in 0 until size) {
                        for (y in 0 until size) {
                            setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading || bitmap == null) {
            Text("Loading...", color = Color.Gray, fontSize = 12.sp)
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddKeywordDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = {},
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.width(600.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ADD SEARCH ITEM", style = MaterialTheme.typography.headlineSmall, color = Color.Yellow)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Type an item name or use your Mic.", color = Color.Gray)

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 24.sp),
                        cursorBrush = SolidColor(Color(0xFF00FFCC)),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text("Enter Item Name...", color = Color.DarkGray, fontSize = 24.sp)
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { if (text.isNotBlank()) onAdd(text) }) {
                        Text("ADD ITEM")
                    }
                    Button(onClick = onDismiss) {
                        Text("CANCEL")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WebhookSettingsDialog(currentUrl: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(currentUrl) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = {},
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.width(700.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("DISCORD NOTIFICATIONS", style = MaterialTheme.typography.headlineSmall, color = Color.Cyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Enter Discord Webhook URL for phone alerts.", color = Color.Gray)

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color(0xFF00FFCC)),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text("Paste Webhook URL here...", color = Color.DarkGray, fontSize = 16.sp)
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { onSave(text) }) {
                        Text("SAVE URL")
                    }
                    Button(onClick = onDismiss) {
                        Text("CANCEL")
                    }
                }
            }
        }
    }
}

// Alert sounds — escalating urgency: watchlist < glitch < unicorn
fun playAlertSound(isGlitch: Boolean, isUnicorn: Boolean = false) {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        when {
            isUnicorn -> {
                // MAXIMUM URGENCY: rapid triple alarm for unicorns
                toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 300)
            }
            isGlitch -> {
                // Urgent: alert tone for regular glitches
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            }
            else -> {
                // Gentle: single pip for watchlist matches
                toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
