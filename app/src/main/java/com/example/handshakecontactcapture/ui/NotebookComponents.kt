package com.example.handshakecontactcapture.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.handshakecontactcapture.R

fun countLabel(count: Int, singular: String) = "$count $singular${if (count == 1) "" else "s"}"

@Composable
fun BrandMark(size: Int = 40) {
    Surface(shape = RoundedCornerShape((size / 3).dp), color = androidx.compose.ui.graphics.Color(0xFF123E32)) {
        Image(painterResource(R.drawable.handshake_art), null, Modifier.size(size.dp))
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun NotebookCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InitialBadge(name: String) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "H" }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text(initials, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun StatPill(text: String, attention: Boolean = false) {
    Surface(color = if (attention) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
            color = if (attention) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun ChoiceRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
