package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ClaudeAccent
import com.example.ui.theme.ClaudeAccentBg
import com.example.ui.theme.ClaudeBackground
import com.example.ui.theme.ClaudeCard
import com.example.ui.theme.ClaudeText
import com.example.ui.theme.ClaudeTextSecondary

/** First screen after splash: in-app home launcher, visually like Android home. */
private data class LauncherTile(val tab: Int, val label: String, val icon: ImageVector)

@Composable
fun LauncherHomeScreen(onOpenTab: (Int) -> Unit) {
    val tiles = listOf(
        LauncherTile(0, "Ijarachilar", Icons.Default.List),
        LauncherTile(1, "Skuterlar", Icons.Default.DirectionsBike),
        LauncherTile(2, "Kontraktlar", Icons.Default.Description),
        LauncherTile(3, "Tranzaksiya", Icons.Default.RequestQuote),
        LauncherTile(4, "Otchetlar", Icons.Default.Assessment),
        LauncherTile(5, "Finansi", Icons.Default.AccountBalanceWallet),
        LauncherTile(6, "Sozlamalar", Icons.Outlined.Settings),
        LauncherTile(7, "Tarix", Icons.Default.History),
        LauncherTile(8, "Korzinka", Icons.Default.Delete)
    )
    Column(
        modifier = Modifier.fillMaxSize().background(ClaudeBackground).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(ClaudeAccentBg, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.DirectionsBike, null, tint = ClaudeAccent, modifier = Modifier.size(42.dp))
        }
        Text("Scooter Rent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = ClaudeText)
        Text("Bo'limni tanlang", style = MaterialTheme.typography.bodyMedium, color = ClaudeTextSecondary)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(tiles, key = { it.tab }) { tile ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenTab(tile.tab) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = ClaudeCard)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(tile.icon, tile.label, tint = ClaudeAccent, modifier = Modifier.size(30.dp))
                        Text(tile.label, style = MaterialTheme.typography.labelSmall, color = ClaudeText, maxLines = 2)
                    }
                }
            }
        }
        Text("Pastdagi navigatsiya panelini yuqoriga tortib, bo'limlarni tez almashtiring.", style = MaterialTheme.typography.labelSmall, color = ClaudeTextSecondary)
    }
}
