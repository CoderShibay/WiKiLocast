package com.wikifm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikifm.WikiFMViewModel
import com.wikifm.ui.theme.*

@Composable
fun PlaylistScreen(viewModel: WikiFMViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlist = state.playlist
    val current = state.currentTitle

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(60.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("QUEUE", style = MaterialTheme.typography.displayLarge, color = AccentAmber)
                Text("UP NEXT", style = MaterialTheme.typography.labelSmall)
            }
            if (playlist.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearPlaylist() }) {
                    Text("Clear all", color = AccentRed, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Now playing indicator
        if (current.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentAmber.copy(alpha = 0.12f))
                    .border(1.dp, AccentAmber.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = AccentAmber)
                    Text(current, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (playlist.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.QueueMusic, null, tint = TextMuted, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your queue is empty", style = MaterialTheme.typography.titleMedium, color = TextMuted)
                    Spacer(Modifier.height(4.dp))
                    Text("Add articles from search or suggestions",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(playlist) { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(GlassSurface)
                            .border(1.dp, GlassBorderDim, RoundedCornerShape(14.dp))
                            .clickable { viewModel.playItem(item) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall,
                            color = AccentAmber, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.extract.isNotBlank()) {
                                Text(item.extract, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { viewModel.removeFromPlaylist(item.title) }) {
                            Icon(Icons.Default.Close, "Remove", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
