@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.tishe.audio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val editor get() = (application as TisheApplication).editor
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFFD4B8FF), onPrimary = Color(0xFF38264F),
                primaryContainer = Color(0xFF403150), onPrimaryContainer = Color(0xFFECDCFF),
                secondary = Color(0xFFCDEB9B), onSecondary = Color(0xFF283619),
                secondaryContainer = Color(0xFF303B24), onSecondaryContainer = Color(0xFFD9F4B4),
                tertiary = Color(0xFFF4B7CE), tertiaryContainer = Color(0xFF4E303F),
                onTertiaryContainer = Color(0xFFFFD9E6),
                background = Color(0xFF15121C), surface = Color(0xFF15121C),
                surfaceContainer = Color(0xFF211D29), surfaceContainerHigh = Color(0xFF2B2535),
                onSurface = Color(0xFFF0E9F5), onSurfaceVariant = Color(0xFFC8BED1),
                outlineVariant = Color(0xFF4C4358),
            )
            MaterialExpressiveTheme(colorScheme = colors) {
                val state by editor.state.collectAsStateWithLifecycle()
                EditorScreen(state, editor)
            }
        }
    }
}

@Composable
private fun EditorScreen(state: EditorState, editor: EditorController) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(editor::importTrack)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        it?.let(editor::export)
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
    val loaded = state.name != null
    val enabled = loaded && !state.busy
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Button(
                        onClick = {
                            val title = state.name.orEmpty().substringBeforeLast('.', state.name.orEmpty())
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "track" }
                            exporter.launch("$title-slowed-reverb.wav")
                        }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.FileDownload, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Export WAV", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Text("Tishe", Modifier.weight(1f).padding(start = 10.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Rounded.MusicNote, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(state.name ?: "No track selected", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (loaded) time(state.duration) else "MP3 · WAV · FLAC · OGG · OPUS",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { picker.launch(arrayOf("audio/*", "application/ogg", "application/opus")) }, enabled = !state.busy) {
                            Icon(Icons.Rounded.FolderOpen, "Open track")
                        }
                    }
                    if (!loaded) {
                        Record(0.0, Modifier.padding(vertical = 20.dp).size(156.dp))
                        FilledTonalButton(onClick = { picker.launch(arrayOf("audio/*", "application/ogg", "application/opus")) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Open audio file")
                        }
                    } else {
                        Record(state.position, Modifier.padding(top = 18.dp, bottom = 10.dp).size(132.dp))
                        var dragPosition by remember(state.name) { mutableStateOf<Float?>(null) }
                        Slider(value = dragPosition ?: state.position.toFloat(),
                            onValueChange = { dragPosition = it },
                            onValueChangeFinished = { dragPosition?.let { editor.seek(it.toDouble()) }; dragPosition = null },
                            valueRange = 0f..state.duration.toFloat().coerceAtLeast(.01f), enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "Track position" })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(time((dragPosition ?: state.position.toFloat()).toDouble()), style = MaterialTheme.typography.labelMedium)
                            Text(time(state.duration), style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(onClick = { editor.seek(0.0) }, enabled = enabled, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Rounded.Replay, "Restart")
                            }
                            Button(onClick = editor::togglePlayback, enabled = enabled, modifier = Modifier.weight(1f).height(64.dp),
                                shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)) {
                                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(30.dp))
                                Spacer(Modifier.width(6.dp)); Text(if (state.playing) "Pause" else "Play", style = MaterialTheme.typography.titleMedium)
                            }
                            FilledTonalIconToggleButton(checked = state.original, onCheckedChange = { editor.effects(original = it) },
                                enabled = enabled, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.CompareArrows, if (state.original) "Hear effects" else "Hear original")
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            FilledTonalIconToggleButton(checked = state.repeat, onCheckedChange = editor::repeat,
                                enabled = enabled, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.RepeatOne, if (state.repeat) "Disable repeat" else "Enable repeat")
                            }
                        }
                    }
                }
            }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(36.dp))
                    Text(state.message ?: "Processing…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Effects", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { editor.effects(.8f, .3f, .7f, extra = ExtraEffects()) }, enabled = enabled) { Text("Reset") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset("Soft", enabled) { editor.effects(.9f, .2f, .55f, extra = ExtraEffects()) }
                Preset("Night", enabled) { editor.effects(.8f, .35f, .75f, extra = ExtraEffects()) }
                Preset("Dream", enabled) { editor.effects(.65f, .5f, .85f, extra = ExtraEffects()) }
            }
            EffectCard("Speed", Icons.Rounded.Speed,
                String.format(Locale.US, "%.2f×", state.speed), state.speed, .5f..1.25f,
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                enabled) { editor.effects(speed = it) }
            EffectCard("Reverb", Icons.Rounded.Waves,
                "${(state.wet * 100).roundToInt()}%", state.wet, 0f..1f,
                MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                enabled) { editor.effects(wet = it) }
            EffectCard("Room size", Icons.Rounded.SurroundSound,
                "${(state.room / .95f * 100).roundToInt()}%", state.room, 0f..0.95f,
                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                enabled) { editor.effects(room = it) }
            EffectCard("Bass boost", Icons.Rounded.Equalizer,
                "${(state.extra.bass * 100).roundToInt()}%", state.extra.bass, 0f..1f,
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                enabled) { editor.effects(extra = state.extra.copy(bass = it)) }
            EffectCard("Echo", Icons.Rounded.SpatialAudio,
                "${(state.extra.echo * 100).roundToInt()}%", state.extra.echo, 0f..1f,
                MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                enabled) { editor.effects(extra = state.extra.copy(echo = it)) }
            EffectCard("Low-pass", Icons.Rounded.Tune,
                "${(state.extra.lowpass * 100).roundToInt()}%", state.extra.lowpass, 0f..1f,
                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                enabled) { editor.effects(extra = state.extra.copy(lowpass = it)) }
            EffectCard("Lo-fi", Icons.Rounded.Radio,
                "${(state.extra.lofi * 100).roundToInt()}%", state.extra.lofi, 0f..1f,
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                enabled) { editor.effects(extra = state.extra.copy(lofi = it)) }
            EffectCard("Flanger", Icons.Rounded.GraphicEq,
                "${(state.extra.flanger * 100).roundToInt()}%", state.extra.flanger, 0f..1f,
                MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                enabled) { editor.effects(extra = state.extra.copy(flanger = it)) }
        }
    }
}

@Composable
private fun Preset(label: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label)
    }
}

@Composable
private fun EffectCard(title: String, icon: ImageVector, label: String,
                       value: Float, range: ClosedFloatingPointRange<Float>, color: Color,
                       foreground: Color, enabled: Boolean, change: (Float) -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = color, contentColor = foreground) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(26.dp))
                Text(title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(value = value, onValueChange = change, enabled = enabled, valueRange = range,
                colors = SliderDefaults.colors(thumbColor = foreground, activeTrackColor = foreground),
                modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = title })
        }
    }
}

@Composable
private fun Record(position: Double, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val center = MaterialTheme.colorScheme.secondary
    Canvas(modifier.graphicsLayer { rotationZ = (position * 12 % 360).toFloat() }) {
        drawCircle(primary.copy(alpha = .12f))
        for (fraction in listOf(.85f, .73f, .61f)) {
            drawCircle(primary.copy(alpha = .3f), radius = size.minDimension / 2 * fraction, style = Stroke(2.dp.toPx()))
        }
        drawCircle(primary, radius = size.minDimension * .22f)
        drawCircle(center, radius = size.minDimension * .07f)
        drawCircle(center, radius = 6.dp.toPx(), center = Offset(size.width * .5f, size.height * .14f))
    }
}

internal fun time(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
