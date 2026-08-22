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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikifm.WikiFMViewModel
import com.wikifm.data.ArticleItem
import com.wikifm.data.SearchResult
import com.wikifm.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: WikiFMViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var showSleepTimer by remember { mutableStateOf(false) }
    var isBookmarked by remember(state.currentTitle) { mutableStateOf(viewModel.isCurrentBookmarked()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))

        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("WIKILOCAST", style = MaterialTheme.typography.displayLarge, color = AccentAmber)
                Text("WIKIPEDIA RADIO", style = MaterialTheme.typography.labelSmall)
            }
            // Sleep timer indicator/button
            if (state.sleepTimerSeconds > 0) {
                val m = state.sleepTimerSeconds / 60
                val s = state.sleepTimerSeconds % 60
                TextButton(onClick = { showSleepTimer = true }) {
                    Text("%d:%02d".format(m, s), color = AccentAmber, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                IconButton(onClick = { showSleepTimer = true }) {
                    Icon(Icons.Default.Bedtime, "Sleep timer", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
            IconButton(onClick = {
                isBookmarked = !isBookmarked
                viewModel.toggleBookmark()
            }) {
                Icon(
                    if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    "Bookmark", tint = if (isBookmarked) AccentAmber else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Search ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search Wikipedia…", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentAmber, unfocusedBorderColor = GlassBorder,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentAmber,
                    unfocusedContainerColor = GlassSurface, focusedContainerColor = GlassSurface
                ),
                shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = { query = ""; viewModel.clearSearch() }) {
                        Icon(Icons.Default.Close, "Clear", tint = TextMuted)
                    }
                }
            )
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(AccentAmber).clickable { if (query.isNotBlank()) viewModel.search(query) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Search, "Search", tint = BgDeep) }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(GlassSurface).border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                    .clickable { query = ""; viewModel.playRandom() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Shuffle, "Random", tint = TextSecondary) }
        }

        Spacer(Modifier.height(16.dp))

        // ── Search results or Player ──
        when {
            isSearching -> {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentAmber, strokeWidth = 2.dp)
                }
            }
            searchResults.isNotEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    searchResults.forEach { result ->
                        SearchResultRow(
                            result = result,
                            onPlay = { viewModel.playTitle(result.title) },
                            onQueue = { viewModel.addToPlaylist(ArticleItem(result.title, result.cleanSnippet)) }
                        )
                    }
                }
            }
            else -> {
                PlayerBody(viewModel = viewModel, state = state, onAddSuggestionToQueue = { item ->
                    viewModel.addToPlaylist(item)
                })
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    // ── Sleep timer ──
    if (showSleepTimer) {
        SleepTimerSheet(
            activeSecs = state.sleepTimerSeconds,
            onSelect = { viewModel.setSleepTimer(it); showSleepTimer = false },
            onDismiss = { showSleepTimer = false }
        )
    }
}

@Composable
private fun PlayerBody(
    viewModel: WikiFMViewModel,
    state: com.wikifm.service.WikiFMState,
    onAddSuggestionToQueue: (ArticleItem) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Now playing glass card
        GlassCard(Modifier.fillMaxWidth().defaultMinSize(minHeight = 160.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(if (state.isPlaying) AccentGreen else TextMuted))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isPlaying) "ON AIR" else "STANDBY",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.isPlaying) AccentGreen else TextMuted)
                }
                Spacer(Modifier.height(10.dp))
                AnimatedContent(state.currentTitle.ifBlank { "Search or shuffle to begin" }, label = "title") { t ->
                    Text(t, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                when {
                    state.isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentAmber, trackColor = GlassBorder)
                    state.error != null -> Text(state.error!!, color = AccentRed, style = MaterialTheme.typography.bodyMedium)
                    state.currentExtract.isNotBlank() -> Text(state.currentExtract,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Suggestions
        if (state.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("SUGGESTED NEXT", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Start).padding(start = 2.dp, bottom = 6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp)
            ) {
                items(state.suggestions) { item ->
                    SuggestionChip(
                        title = item.title,
                        onPlay = { viewModel.playTitle(item.title) },
                        onQueue = { onAddSuggestionToQueue(item) }
                    )
                }
            }
        }

        // Progress bar
        if (state.currentExtract.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            PlaybackProgressBar(
                progress = state.playbackProgress,
                articleLength = state.currentExtract.length,
                speechRate = state.speechRate,
                onSeek = { viewModel.seekTo(it) }
            )
        }

        Spacer(Modifier.height(20.dp))

        // Transport controls
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlButton(icon = Icons.Default.Shuffle, desc = "Random", onClick = { viewModel.playRandom() })
            Box(
                modifier = Modifier.size(76.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(AccentAmber, AccentAmberDeep)))
                    .clickable {
                        when {
                            state.isPlaying -> viewModel.pause()
                            state.currentExtract.isNotBlank() -> viewModel.resume()
                            else -> viewModel.playRandom()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null, tint = BgDeep, modifier = Modifier.size(42.dp))
            }
            ControlButton(icon = Icons.Default.SkipNext, desc = "Skip", onClick = { viewModel.skip() })
        }

        // Queue current article button
        if (state.currentTitle.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.addToPlaylist(ArticleItem(state.currentTitle, state.currentExtract)) },
                border = BorderStroke(1.dp, GlassBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Icon(Icons.Default.AddToQueue, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add to Queue", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(28.dp))

        // Speed
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("SPEED  ${String.format("%.1f", state.speechRate)}×", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Slider(value = state.speechRate, onValueChange = { viewModel.setSpeechRate(it) },
                    valueRange = 0.5f..2.5f, steps = 7,
                    colors = SliderDefaults.colors(thumbColor = AccentAmber, activeTrackColor = AccentAmber, inactiveTrackColor = GlassBorder))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Slow  0.5×", style = MaterialTheme.typography.labelSmall)
                    Text("2.5×  Fast", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Jump interval
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("AUTO-JUMP", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(0 to "End", 1 to "1 min", 3 to "3 min", 5 to "5 min", 10 to "10 min").forEach { (m, l) ->
                        FilterChip(
                            selected = state.jumpIntervalMinutes == m,
                            onClick = { viewModel.setJumpInterval(m) },
                            label = { Text(l, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentAmber, selectedLabelColor = BgDeep,
                                containerColor = GlassSurface, labelColor = TextSecondary
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(title: String, onPlay: () -> Unit, onQueue: () -> Unit) {
    Column(
        modifier = Modifier.width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorderDim, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(42.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallIconButton(Icons.Default.PlayArrow, "Play", AccentAmber) { onPlay() }
            SmallIconButton(Icons.Default.AddToQueue, "Queue", TextSecondary) { onQueue() }
        }
    }
}

@Composable
fun SearchResultRow(result: SearchResult, onPlay: () -> Unit, onQueue: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
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
private fun PlaybackProgressBar(
    progress: Float,
    articleLength: Int,
    speechRate: Float,
    onSeek: (Float) -> Unit
) {
    // Rough estimate: ~14 chars/sec at 1x speed
    val totalSecs = (articleLength / (14f * speechRate)).toInt().coerceAtLeast(1)
    val currentSecs = (progress * totalSecs).toInt()

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress,
            onValueChange = onSeek,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = TextPrimary,
                activeTrackColor = TextPrimary,
                inactiveTrackColor = GlassBorder
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(currentSecs), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(totalSecs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(52.dp).clip(CircleShape)
            .background(GlassSurface).border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = TextPrimary, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape)
            .background(GlassSurface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(activeSecs: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BgDeep,
        dragHandle = {
            Box(Modifier.padding(top=12.dp, bottom=8.dp).size(width=40.dp, height=4.dp)
                .clip(RoundedCornerShape(2.dp)).background(GlassBorder))
        }
    ) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes",
                   45 to "45 minutes", 60 to "1 hour", 90 to "1.5 hours").forEach { (min, label) ->
                val isCurrent = if (min == 0) activeSecs == 0 else activeSecs > 0 && activeSecs <= min * 60
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isCurrent) AccentAmber.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onSelect(min) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium,
                        color = if (isCurrent) AccentAmber else TextPrimary, modifier = Modifier.weight(1f))
                    if (isCurrent) Icon(Icons.Default.Check, null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = GlassBorderDim, thickness = 0.5.dp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
