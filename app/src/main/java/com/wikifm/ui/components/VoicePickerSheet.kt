package com.wikifm.ui.components

import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.wikifm.ui.theme.*

fun Voice.displayName(): String {
    val country = when (locale.country.uppercase()) {
        "US" -> "American"
        "GB" -> "British"
        "AU" -> "Australian"
        "IN" -> "Indian"
        "CA" -> "Canadian"
        "IE" -> "Irish"
        "NZ" -> "New Zealand"
        else -> locale.displayCountry.ifEmpty { "English" }
    }
    val q = when {
        quality >= Voice.QUALITY_VERY_HIGH -> "Premium"
        quality >= Voice.QUALITY_HIGH      -> "High Quality"
        else                               -> "Standard"
    }
    val id = name.replace(Regex("en-[a-zA-Z]+-x-"), "")
                 .replace(Regex("-(local|network|embedded)"), "")
                 .uppercase().take(4)
    return "$country English  ·  $q  [$id]"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    voices: List<Voice>,
    selectedVoiceName: String,
    onSelect: (Voice) -> Unit,
    onPreview: (Voice) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgDeep,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GlassBorder)
            )
        }
    ) {
        Text(
            "Choose a Voice",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        if (voices.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No voices found.\nInstall Google TTS from Play Store for better voices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary)
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            items(voices) { voice ->
                val isSelected = voice.name == selectedVoiceName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) AccentAmber.copy(alpha = 0.15f) else GlassSurface)
                        .border(1.dp, if (isSelected) AccentAmber else GlassBorderDim, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(voice.displayName(), style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) AccentAmber else TextPrimary)
                    }
                    TextButton(onClick = { onPreview(voice) }) {
                        Text("Preview", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { onSelect(voice); onDismiss() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) AccentAmber else GlassSurfaceHi
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(if (isSelected) "Selected" else "Use",
                            color = if (isSelected) BgDeep else TextPrimary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
