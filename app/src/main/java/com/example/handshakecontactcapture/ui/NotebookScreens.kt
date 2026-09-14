package com.example.handshakecontactcapture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.handshakecontactcapture.ai.jsonStrings
import com.example.handshakecontactcapture.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventsOverview(events: List<Event>, connections: List<Connection>, onEvent: (Event) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("YOUR EVENT NOTEBOOK", style = MaterialTheme.typography.labelMedium)
                    Text("Make every\nintroduction count.", style = MaterialTheme.typography.headlineLarge)
                    Text("Remember the people. Keep the context. Take the next step.", style = MaterialTheme.typography.bodyLarge)
                    if (events.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatPill(countLabel(events.size, "event"))
                        StatPill(countLabel(connections.size, "connection"))
                        val open = connections.count { it.encounter.followUp.isNotBlank() && !it.encounter.done }
                        if (open > 0) StatPill(countLabel(open, "follow-up"), attention = true)
                    }
                }
            }
        }
        item { SectionHeading("Your events", "A place for every introduction.") }
        if (events.isEmpty()) item { NotebookCard("Start with your next event", "Tap + New event, then scan a card or add a connection. Your notebook is saved on this device and works offline.") }
        items(events, key = { it.id }) { event ->
            Card(onClick = { onEvent(event) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    InitialBadge(event.name)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(event.name, style = MaterialTheme.typography.titleLarge)
                        val subtitle = listOf(event.location, event.dates).filter { it.isNotBlank() }.joinToString(" · ")
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(countLabel(connections.count { it.encounter.eventId == event.id }, "connection"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Saved on this device · Available offline", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventNotebook(event: Event, connections: List<Connection>, captures: List<Capture>, saving: Boolean,
    query: String, onQuery: (String) -> Unit, followUpsOnly: Boolean, onFilter: () -> Unit,
    onEdit: () -> Unit, onExport: () -> Unit, onScan: () -> Unit, onCapture: (Capture) -> Unit, onContact: (Connection) -> Unit) {
    val visible = connections.filter {
        (!followUpsOnly || (it.encounter.followUp.isNotBlank() && !it.encounter.done)) &&
            listOf(it.contact.name, it.contact.company, it.contact.email, it.contact.phone, it.encounter.notes, it.encounter.followUp)
                .any { value -> value.contains(query, ignoreCase = true) }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 112.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ScreenHeading(event.name, listOf(event.location, event.dates).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Your connections, context, and next steps." })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(countLabel(connections.size, "connection"))
                StatPill(countLabel(connections.count { it.encounter.followUp.isNotBlank() && !it.encounter.done }, "open follow-up"), true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, enabled = !saving) { Text("Edit event") }
                TextButton(onClick = onExport, enabled = connections.isNotEmpty()) { Text("Export event") }
            }
        }
        item { Button(enabled = !saving, onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Scan card / fact sheet") } }
        if (captures.isNotEmpty()) item { SectionHeading("Saved photo batches") }
        items(captures, key = { "capture-${it.id}" }) { batch ->
            OutlinedCard(onClick = { onCapture(batch) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Photo batch · ${jsonStrings(batch.images).size} photos", style = MaterialTheme.typography.titleSmall)
                    Text(when(batch.state) {
                        "draft" -> "Add photos and extract"
                        "review" -> "Review extracted contacts"
                        "failed" -> "Extraction needs attention"
                        else -> "Extraction queued or processing"
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeading("Connections") }
        item { OutlinedTextField(query, onQuery, label = { Text("Search this event") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("Clear") } }) }
        item { FilterChip(selected = followUpsOnly, onClick = onFilter, label = { Text("Open follow-ups") }) }
        if (visible.isEmpty()) item { NotebookCard(if (connections.isEmpty()) "Who did you meet?" else "No matching connections",
            if (connections.isEmpty()) "Scan a card or add a person or company. Capture the details while they are fresh." else "Try another search or turn off the follow-up filter.") }
        items(visible, key = { it.encounter.id }) { connection ->
            Card(onClick = { onContact(connection) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    InitialBadge(connection.contact.name.ifBlank { connection.contact.company })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(connection.contact.name.ifBlank { connection.contact.company }, style = MaterialTheme.typography.titleMedium)
                        if (connection.contact.name.isNotBlank()) Text(connection.contact.company.ifBlank { "Independent contact" },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (connection.encounter.notes.isNotBlank()) Text(connection.encounter.notes, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (connection.encounter.followUp.isNotBlank()) {
                            HorizontalDivider(Modifier.padding(vertical = 3.dp))
                            Text(if (connection.encounter.done) "Follow-up completed" else "Next: ${connection.encounter.followUp}",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
