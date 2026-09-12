package app.knock.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import kotlinx.coroutines.delay

@Composable
fun CaptureScreen(vm: MainViewModel, onParsed: () -> Unit, onBack: () -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    var typing by remember { mutableStateOf(!SpeechRecognizer.isRecognitionAvailable(ctx)) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var finalText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var rms by remember { mutableFloatStateOf(0f) }
    var typed by remember { mutableStateOf("") }
    var micGranted by remember { mutableStateOf(Perms.mic(ctx)) }

    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun start() {
        val r = recognizer ?: run { typing = true; return }
        error = null; partial = ""; finalText = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { rms = rmsdB }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(code: Int) {
                listening = false
                error = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again or type."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs a network on this device — or type instead."
                    else -> "Speech error ($code) — you can type instead."
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                finalText = list?.firstOrNull() ?: partial
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.voiceLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        r.startListening(intent)
        listening = true
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok; if (ok) start() else typing = true
    }

    LaunchedEffect(Unit) {
        if (!typing) { if (micGranted) start() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }
    LaunchedEffect(finalText) {
        if (finalText.isNotBlank()) { vm.parse(finalText); onParsed() }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = c.text) }
            Text(if (typing) "Type your tasks" else if (listening) "Listening…" else "Tap the mic to start",
                style = MaterialTheme.typography.titleLarge, color = c.text)
        }

        if (typing) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = typed, onValueChange = { typed = it }, modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("call the bank at 10:30, submit assignment by 2, gym at 6…") },
                colors = knockFieldColors(), shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recognizer != null) OutlinedButton(onClick = { typing = false; if (micGranted) start() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Icon(Icons.Filled.Mic, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Speak instead")
                }
                Button(
                    onClick = { vm.parse(typed); onParsed() }, enabled = typed.isNotBlank(), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn)
                ) { Text("Split into tasks") }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Waveform(rms, listening)
            Spacer(Modifier.height(28.dp))
            Text(
                if (partial.isBlank()) (if (listening) "Say everything in one go — Knock splits it up." else "") else partial,
                style = MaterialTheme.typography.headlineMedium, color = if (partial.isBlank()) c.secondary else c.text,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            )
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = c.overdue) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { recognizer?.cancel(); listening = false; typing = true }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Type")
                }
                Box(Modifier.size(84.dp).background(c.accent.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
                    FloatingActionButton(
                        onClick = { if (listening) recognizer?.stopListening() else start() },
                        containerColor = c.accent, contentColor = c.accentOn, shape = CircleShape, modifier = Modifier.size(64.dp)
                    ) { Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = "Mic") }
                }
                Spacer(Modifier.width(88.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Waveform(rms: Float, active: Boolean) {
    val c = LocalKnock.current
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) { while (active) { delay(90); phase++ } }
    val level = ((rms + 2f) / 12f).coerceIn(0.08f, 1f)
    val anim by animateFloatAsState(if (active) level else 0.08f, label = "rms")
    Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(24) { i ->
            val wobble = 0.5f + 0.5f * kotlin.math.sin((phase + i * 1.7f) * 0.6f)
            val h = (8 + 56 * anim * wobble).dp
            Box(Modifier.width(5.dp).height(h).background(c.accent, RoundedCornerShape(3.dp)))
        }
    }
}
