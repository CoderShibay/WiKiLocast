package com.wikifm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikifm.WikiFMViewModel
import com.wikifm.data.ArticleItem
import com.wikifm.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LibraryScreen(viewModel: WikiFMViewModel) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    var tabIndex by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(60.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LIBRARY", style = MaterialTheme.typography.displayLarge, color = AccentAmber)
                Text("SAVED & HISTORY", style = MaterialTheme.typography.labelSmall)
            }
            if (tabIndex == 1 && history.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearHistory() }) {
                    Text("Clear", color = AccentRed, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = GlassSurface,
            contentColor = AccentAmber,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[tabIndex]),
                    color = AccentAmber, height = 2.dp
                )
            }
        ) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 },
                text = { Text("Bookmarks", style = MaterialTheme.typography.labelSmall,
                    color = if (tabIndex == 0) AccentAmber else TextSecondary) })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 },
                text = { Text("History", style = MaterialTheme.typography.labelSmall,
                    color = if (tabIndex == 1) AccentAmber else TextSecondary) })
        }

        Spacer(Modifier.height(12.dp))

        when (tabIndex) {
            0 -> ArticleList(
                items = bookmarks,
                emptyIcon = Icons.Default.BookmarkBorder,
                emptyMessage = "No bookmarks yet",
                emptyHint = "Bookmark articles while listening",
                onPlay = { viewModel.playItem(it) },
                onQueue = { viewModel.addToPlaylist(it) },
                onRemove = { viewModel.removeBookmark(it.title) }
            )
            1 -> ArticleList(
                items = history,
                emptyIcon = Icons.Default.History,
                emptyMessage = "No history yet",
                emptyHint = "Articles you listen to appear here",
                onPlay = { viewModel.playItem(it) },
                onQueue = { viewModel.addToPlaylist(it) },
                onRemove = null
            )
        }
    }
}

@Composable
private fun ArticleList(
    items: List<ArticleItem>,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyMessage: String,
    emptyHint: String,
    onPlay: (ArticleItem) -> Unit,
    onQueue: (ArticleItem) -> Unit,
    onRemove: ((ArticleItem) -> Unit)?
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(emptyIcon, null, tint = TextMuted, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(emptyMessage, style = MaterialTheme.typography.titleMedium, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(emptyHint, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GlassSurface)
                        .border(1.dp, GlassBorderDim, RoundedCornerShape(14.dp))
                        .clickable { onPlay(item) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.timestamp > 0) {
                            Text(
                                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(item.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    IconButton(onClick = { onQueue(item) }) {
                        Icon(Icons.Default.AddToQueue, "Queue", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onPlay(item) }) {
                        Icon(Icons.Default.PlayArrow, "Play", tint = AccentAmber, modifier = Modifier.size(20.dp))
                    }
                    if (onRemove != null) {
                        IconButton(onClick = { onRemove(item) }) {
                            Icon(Icons.Default.Delete, "Remove", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
