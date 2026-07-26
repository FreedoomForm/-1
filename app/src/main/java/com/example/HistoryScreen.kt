package com.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.BusinessOperation
import com.example.ui.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact phone-first chronological history; details open from a selected row. */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val items = viewModel.items.collectAsStateWithLifecycle().value
    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Tarix hali bo'sh", style = MaterialTheme.typography.titleMedium)
            Text("To'lovlar, o'zgarishlar va amallar shu yerda ko'rinadi.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { "${it.kind}-${it.sourceId}" }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(item.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(formatter.format(Date(item.timestamp)), style = MaterialTheme.typography.labelSmall)
                    }
                    item.amountMinor?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("${BusinessOperation.fromMinor(it).toLong()} UZS", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
