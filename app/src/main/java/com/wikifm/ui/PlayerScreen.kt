package com.wikifm.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikifm.WikiFMViewModel
import com.wikifm.data.ArticleItem
import com.wikifm.data.SearchResult
import com.wikifm.service.WikiFMState
import com.wikifm.ui.theme.*
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: WikiFMViewModel) {
    val state     by viewModel.state.collectAsStateWithLifecycle()
    val results   by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.isSearching.collectAsStateWithLifecycle()

    var query        by remember { mutableStateOf("") }
    var showSleep    by remember { mutableStateOf(false) }
    var isBookmarked by remember(state.currentTitle) { mutableStateOf(viewModel.isCurrentBookmarked()) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Scrollable content ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(52.dp))

            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WIKILOCAST", style = MaterialTheme.typography.displayLarge, color = AccentAmber)
                    Text("WIKIPEDIA RADIO", style = MaterialTheme.typography.labelSmall)
                }
                if (state.sleepTimerSeconds > 0) {
                    val m = state.sleepTimerSeconds / 60; val s = state.sleepTimerSeconds % 60
                    TextButton(onClick = { showSleep = true }) {
                        Text("%d:%02d".format(m, s), color = AccentAmber, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    IconButton(onClick = { showSleep = true }) {
                        Icon(Icons.Default.Bedtime, "Sleep", tint = TextSecondary, modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = { isBookmarked = !isBookmarked; viewModel.toggleBookmark() }) {
                    Icon(
                        if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        "Bookmark",
                        tint = if (isBookmarked) AccentAmber else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Search bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search Wikipedia…", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentAmber, unfocusedBorderColor = GlassBorder,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = AccentAmber,
                        unfocusedContainerColor = GlassSurface, focusedContainerColor = GlassSurface
                    ),
                    trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { query = ""; viewModel.clearSearch() }) {
                            Icon(Icons.Default.Close, "Clear", tint = TextMuted)
                        }
                    }
                )
                SearchBtn { if (query.isNotBlank()) viewModel.search(query) }
                ShuffleBtn { query = ""; viewModel.playRandom() }
            }

            Spacer(Modifier.height(16.dp))

            when {
                searching -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentAmber, strokeWidth = 2.dp)
                }
                results.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    results.forEach { r ->
                        SearchResultRow(
                            result = r,
                            onPlay  = { viewModel.playTitle(r.title) },
                            onQueue = { viewModel.addToPlaylist(ArticleItem(r.title, r.cleanSnippet)) }
                        )
                    }
                }
                else -> NowPlayingArea(state, viewModel)
            }

            Spacer(Modifier.height(12.dp))
        }

        // ── Fixed bottom controls ──────────────────────────────────────
        HorizontalDivider(color = GlassBorderDim, thickness = 0.5.dp)
        PlayerFooter(state, viewModel)
    }

    if (showSleep) {
        SleepTimerSheet(
            activeSecs = state.sleepTimerSeconds,
            onSelect   = { viewModel.setSleepTimer(it); showSleep = false },
            onDismiss  = { showSleep = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Now-playing scrollable content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NowPlayingArea(state: WikiFMState, viewModel: WikiFMViewModel) {
    // Article card
    GlassCard(Modifier.fillMaxWidth().defaultMinSize(minHeight = 130.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape)
                    .background(if (state.isPlaying) AccentGreen else TextMuted))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.isPlaying) "ON AIR" else "STANDBY",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isPlaying) AccentGreen else TextMuted
                )
                Spacer(Modifier.weight(1f))
                if (state.currentTitle.isNotBlank()) {
                    SmallIconButton(Icons.Default.AddToQueue, "Queue", TextMuted) {
                        viewModel.addToPlaylist(ArticleItem(state.currentTitle, state.currentExtract))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AnimatedContent(state.currentTitle.ifBlank { "Search above or tap shuffle" }, label = "t") { t ->
                Text(t, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentAmber, trackColor = GlassBorder)
                state.error != null -> Text(state.error!!, color = AccentRed, style = MaterialTheme.typography.bodyMedium)
                state.currentExtract.isNotBlank() -> Text(
                    state.currentExtract, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // Suggestions
    if (state.suggestions.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("SUGGESTED NEXT", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(state.suggestions) { item ->
                SuggestionChip(
                    title  = item.title,
                    onPlay = { viewModel.playTitle(item.title) },
                    onQueue = { viewModel.addToPlaylist(item) }
                )
            }
        }
    }

    // Auto-jump
    Spacer(Modifier.height(16.dp))
    Text("AUTO-JUMP", style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        listOf(0 to "End", 1 to "1 min", 3 to "3 min", 5 to "5 min", 10 to "10 min").forEach { (m, l) ->
            FilterChip(
                selected = state.jumpIntervalMinutes == m,
                onClick  = { viewModel.setJumpInterval(m) },
                label    = { Text(l, style = MaterialTheme.typography.labelSmall) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentAmber, selectedLabelColor = BgDeep,
                    containerColor = GlassSurface, labelColor = TextSecondary
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixed footer
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlayerFooter(state: WikiFMState, viewModel: WikiFMViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgDeep)
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Speed buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(1f to "1×", 1.5f to "1.5×", 2f to "2×", 2.5f to "2.5×", 3f to "3×").forEach { (speed, label) ->
                val selected = abs(state.speechRate - speed) < 0.1f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) AccentAmber else GlassSurface)
                        .border(1.dp, if (selected) AccentAmber else GlassBorderDim, RoundedCornerShape(8.dp))
                        .clickable { viewModel.setSpeechRate(speed) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label,
                        color = if (selected) BgDeep else TextSecondary,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Progress bar
        val totalSecs   = (state.currentExtract.length / (14f * state.speechRate)).toInt().coerceAtLeast(1)
        val currentSecs = (state.playbackProgress * totalSecs).toInt()

        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = state.playbackProgress,
                onValueChange = { viewModel.seekTo(it) },
                modifier = Modifier.fillMaxWidth().height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = TextPrimary,
                    activeTrackColor = TextPrimary,
                    inactiveTrackColor = GlassBorder
                )
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), Arrangement.SpaceBetween) {
                Text(fmtTime(currentSecs), style = MaterialTheme.typography.labelSmall)
                Text(fmtTime(totalSecs),   style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(14.dp))

        // Transport
        Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportBtn(Icons.Default.Shuffle, "Shuffle", 44.dp) { viewModel.playRandom() }

            // Big play/pause
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(AccentAmber, AccentAmberDeep)))
                    .clickable {
                        when {
                            state.isPlaying                     -> viewModel.pause()
                            state.currentExtract.isNotBlank()   -> viewModel.resume()
                            else                                -> viewModel.playRandom()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null, tint = BgDeep, modifier = Modifier.size(36.dp)
                )
            }

            TransportBtn(Icons.Default.SkipNext, "Skip", 44.dp) { viewModel.skip() }
        }
    }
}

private fun fmtTime(seconds: Int): String {
    val m = seconds / 60; val s = seconds % 60
    return "%d:%02d".format(m, s)
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable small components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SuggestionChip(title: String, onPlay: () -> Unit, onQueue: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorderDim, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(44.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallIconButton(Icons.Default.PlayArrow, "Play",  AccentAmber) { onPlay() }
            SmallIconButton(Icons.Default.AddToQueue, "Queue", TextSecondary) { onQueue() }
        }
    }
}

@Composable
fun SearchResultRow(result: SearchResult, onPlay: () -> Unit, onQueue: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorderDim, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (result.cleanSnippet.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(result.cleanSnippet, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        SmallIconButton(Icons.Default.AddToQueue, "Queue", TextSecondary) { onQueue() }
        Spacer(Modifier.width(4.dp))
        SmallIconButton(Icons.Default.PlayArrow, "Play", AccentAmber) { onPlay() }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0x1EFFFFFF), Color(0x0AFFFFFF))))
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
    ) { content() }
}

@Composable
fun SmallIconButton(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape)
            .background(GlassSurface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun TransportBtn(icon: ImageVector, desc: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(GlassSurface)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = TextPrimary, modifier = Modifier.size(size * 0.52f)) }
}

@Composable
private fun SearchBtn(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(AccentAmber).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Search, "Search", tint = BgDeep) }
}

@Composable
private fun ShuffleBtn(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
            .background(GlassSurface).border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Shuffle, "Random", tint = TextSecondary) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(activeSecs: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = BgDeep,
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp)).background(GlassBorder))
        }
    ) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes",
                45 to "45 minutes", 60 to "1 hour", 90 to "1.5 hours").forEach { (min, label) ->
                val active = if (min == 0) activeSecs == 0 else activeSecs > 0 && activeSecs <= min * 60
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) AccentAmber.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelect(min) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium,
                        color = if (active) AccentAmber else TextPrimary, modifier = Modifier.weight(1f))
                    if (active) Icon(Icons.Default.Check, null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = GlassBorderDim, thickness = 0.5.dp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
