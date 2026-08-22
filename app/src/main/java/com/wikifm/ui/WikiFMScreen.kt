package com.wikifm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wikifm.WikiFMViewModel
import com.wikifm.ui.theme.*

private data class NavItem(val route: String, val icon: ImageVector, val label: String)

private val navItems = listOf(
    NavItem("player",   Icons.Default.Radio,        "Now Playing"),
    NavItem("playlist", Icons.Default.QueueMusic,   "Queue"),
    NavItem("library",  Icons.Default.AutoStories,  "Library")
)

@Composable
fun WikiFMScreen(viewModel: WikiFMViewModel = viewModel()) {
    val navController = rememberNavController()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepNavy, DeepPurple, Color(0xFF0D0518))))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { BottomBar(navController) }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "player",
                modifier = Modifier.padding(padding)
            ) {
                composable("player")   { PlayerScreen(viewModel) }
                composable("playlist") { PlaylistScreen(viewModel) }
                composable("library")  { LibraryScreen(viewModel) }
            }
        }
    }
}

@Composable
private fun BottomBar(navController: NavController) {
    val current by navController.currentBackStackEntryAsState()
    val route = current?.destination?.route

    NavigationBar(
        containerColor = Color(0xCC080C1A),
        tonalElevation = 0.dp,
        modifier = Modifier.height(64.dp)
    ) {
        navItems.forEach { item ->
            NavigationBarItem(
                icon = { Icon(item.icon, item.label, modifier = Modifier.size(22.dp)) },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                selected = route == item.route,
                onClick = { navController.navigate(item.route) { launchSingleTop = true; restoreState = true } },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentAmber,
                    selectedTextColor = AccentAmber,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = AccentAmber.copy(alpha = 0.15f)
                )
            )
        }
    }
}
