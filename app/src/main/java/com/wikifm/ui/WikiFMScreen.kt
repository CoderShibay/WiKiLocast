package com.wikifm.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wikifm.WikiFMViewModel
import com.wikifm.data.SearchResult
import com.wikifm.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiFMScreen(viewModel: WikiFMViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // ── Header ──
        Text("WIKI FM", style = MaterialTheme.typography.displayLarge)
        Text("Wikipedia Radio", style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(24.dp))

        // ── Search bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search Wikipedia…", color = OnSurfaceMuted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RadioAmber,
                    unfocusedBorderColor = RadioAmberDim,
                    focusedTextColor = OnSurfaceLight,
                    unfocusedTextColor = OnSurfaceLight,
                    cursorColor = RadioAmber
                ),
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = ""; viewModel.clearSearch() }) {
                            Icon(Icons.Default.Close, "Clear", tint = OnSurfaceMuted)
                        }
                    }
                }
            )
            Button(
                onClick = { if (query.isNotBlank()) viewModel.search(query) },
                enabled = query.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = RadioAmber)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = BackgroundDark)
            }
            IconButton(
                onClick = { query = ""; viewModel.playRandom() },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SurfaceVariantDark)
            ) {
                Icon(Icons.Default.Shuffle, "Random", tint = RadioAmber)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Search results OR Now-playing ──
        if (isSearching) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RadioAmber)
            }
        } else if (searchResults.isNotEmpty()) {
            // Show results for user to pick
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchResults) { result ->
                    SearchResultCard(result = result, onClick = { viewModel.playTitle(result.title) })
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        } else {
            // Now-playing view
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Now Playing Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, RadioAmberDim)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isPlaying) RadioGreen else OnSurfaceMuted)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (state.isPlaying) "ON AIR" else "STANDBY",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.isPlaying) RadioGreen else OnSurfaceMuted
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        AnimatedContent(
                            targetState = state.currentTitle.ifBlank { "Search for a topic above" },
                            label = "title"
                        ) { title ->
                            Text(title, style = MaterialTheme.typography.titleLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                        when {
                            state.isLoading -> LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = RadioAmber, trackColor = SurfaceVariantDark
                            )
                            state.error != null -> Text(
                                state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            state.currentExtract.isNotBlank() -> Text(
                                state.currentExtract,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4, overflow = TextOverflow.Ellipsis,
                                color = OnSurfaceMuted
                            )
                            else -> Text(
                                "Tap 🔀 for a random article, or search for a topic.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceMuted
                            )
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))

                // Transport controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.playRandom() },
                        modifier = Modifier.size(52.dp).clip(CircleShape).background(SurfaceVariantDark)
                    ) {
                        Icon(Icons.Default.Shuffle, "Random", tint = OnSurfaceLight, modifier = Modifier.size(26.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(RadioAmber, RadioAmberDim)))
                            .clickable {
                                when {
                                    state.isPlaying -> viewModel.pause()
                                    state.currentExtract.isNotBlank() -> viewModel.resume()
                                    else -> viewModel.playRandom()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = BackgroundDark,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.skip() },
                        modifier = Modifier.size(52.dp).clip(CircleShape).background(SurfaceVariantDark)
                    ) {
                        Icon(Icons.Default.SkipNext, "Skip", tint = OnSurfaceLight, modifier = Modifier.size(26.dp))
                    }
                }

                Spacer(Modifier.height(32.dp))

                // Speed
                ControlSection(label = "SPEED  ${String.format("%.1f", state.speechRate)}×") {
                    Slider(
                        value = state.speechRate,
                        onValueChange = { viewModel.setSpeechRate(it) },
                        valueRange = 0.5f..2.5f,
                        steps = 7,
                        colors = SliderDefaults.colors(
                            thumbColor = RadioAmber,
                            activeTrackColor = RadioAmber,
                            inactiveTrackColor = SurfaceVariantDark
                        )
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("0.5×", style = MaterialTheme.typography.labelSmall)
                        Text("2.5×", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Auto-jump
                ControlSection(label = "AUTO-JUMP") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        listOf(0 to "End", 1 to "1 min", 3 to "3 min", 5 to "5 min", 10 to "10 min")
                            .forEach { (minutes, label) ->
                                FilterChip(
                                    selected = state.jumpIntervalMinutes == minutes,
                                    onClick = { viewModel.setJumpInterval(minutes) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = RadioAmber,
                                        selectedLabelColor = BackgroundDark,
                                        containerColor = SurfaceVariantDark,
                                        labelColor = OnSurfaceLight
                                    )
                                )
                            }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, RadioAmberDim)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.cleanSnippet.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        result.cleanSnippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = RadioAmber,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ControlSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}
