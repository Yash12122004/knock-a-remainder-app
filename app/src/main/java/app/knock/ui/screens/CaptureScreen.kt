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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.knock.parse.TaskParser
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import app.knock.ui.theme.knockCard
import kotlinx.coroutines.delay

/**
 * Capture flow:
 *  1. Listening — live partial transcript.
 *  2. Review — after a pause the text stays on screen: Add more (append by voice), Edit (type), Continue (split).
 *  3. Typing — free text with a live "will become N tasks" preview.
 * Speech never auto-advances; only "Continue" / "Split into tasks" leaves this screen.
 */
@Composable
fun CaptureScreen(vm: MainViewModel, onParsed: () -> Unit, onBack: () -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val inAppAvailable = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }

    var text by remember { mutableStateOf("") }          // accumulated transcript (source of truth)
    var typing by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var rms by remember { mutableFloatStateOf(0f) }
    var micGranted by remember { mutableStateOf(Perms.mic(ctx)) }
    var preferOffline by remember { mutableStateOf(true) }

    val recognizer = remember { if (inAppAvailable) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun append(piece: String) {
        val p = piece.trim()
        if (p.isEmpty()) return
        text = if (text.isBlank()) p else text.trimEnd().trimEnd(',', ';', '.') + ", " + p
    }

    val dialogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) append(spoken) else if (text.isBlank()) typing = true
    }
    fun startSystemDialog(): Boolean {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.voiceLang)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your tasks")
        }
        return try { dialogLauncher.launch(i); true } catch (e: Exception) { false }
    }

    fun start() {
        val r = recognizer ?: run { if (!startSystemDialog()) typing = true; return }
        error = null; partial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { rms = rmsdB }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(code: Int) {
                listening = false
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        error = if (text.isBlank()) "Didn't catch that — tap the mic to try again, or type." else null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> error = "Microphone permission needed."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> error = "Speech engine was busy — tap the mic to try again."
                    else -> {
                        // Offline model missing / language unavailable / network → retry online once, then system dialog, then typing.
                        if (preferOffline) { preferOffline = false; start() }
                        else if (!startSystemDialog()) { error = "Speech isn't available on this phone (error $code) — type instead."; typing = true }
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial
                partial = ""
                append(spoken)
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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
        r.startListening(intent)
        listening = true
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok; if (ok) start() else typing = true
    }
    fun speak() { typing = false; if (micGranted) start() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    fun continueToSplit() { recognizer?.cancel(); vm.parse(text); onParsed() }

    LaunchedEffect(Unit) { speak() }

    val chunks = remember(text) { TaskParser.split(text) }
    val reviewing = !typing && !listening && text.isNotBlank()

    Column(Modifier.fillMaxSize().imePadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = c.text) }
            Text(
                when {
                    typing -> "Type your tasks"
                    listening -> if (text.isBlank()) "Listening…" else "Listening for more…"
                    reviewing -> "Got it — anything else?"
                    else -> "Tap the mic to start"
                },
                style = MaterialTheme.typography.titleLarge, color = c.text
            )
        }

        if (typing) {
            // ---------------- typing mode ----------------
            Spacer(Modifier.height(12.dp))
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp).focusRequester(focus),
                placeholder = { Text("call the bank at 10:30, submit assignment by 2, gym at 6…", color = c.secondary) },
                colors = knockFieldColors(), shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )
            ChunkPreview(chunks, Modifier.weight(1f, fill = false))
            if (chunks.isEmpty()) Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { speak() }) {
                    Icon(Icons.Filled.Mic, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Speak")
                }
                Button(
                    onClick = { continueToSplit() }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn)
                ) { Text("Split into tasks") }
            }
        } else {
            // ---------------- listening / review ----------------
            Spacer(Modifier.height(16.dp))
            if (text.isNotBlank() || partial.isNotBlank()) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).knockCard().padding(14.dp).verticalScroll(rememberScrollState())) {
                    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.titleLarge, color = c.text)
                    if (partial.isNotBlank()) Text(
                        (if (text.isNotBlank()) "… " else "") + partial,
                        style = MaterialTheme.typography.titleLarge, color = c.accent
                    )
                }
                if (reviewing) ChunkPreview(chunks, Modifier.padding(top = 8.dp))
            } else {
                Spacer(Modifier.weight(1f))
                Text(
                    if (listening) "Say everything in one go — Knock splits it up." else "",
                    style = MaterialTheme.typography.headlineMedium, color = c.secondary, modifier = Modifier.fillMaxWidth()
                )
            }
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = c.overdue) }
            Spacer(Modifier.weight(1f))
            if (listening) Waveform(rms, true)
            Spacer(Modifier.height(20.dp))

            if (reviewing) {
                // Add more / Edit / Continue
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { speak() }, modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Add more")
                    }
                    OutlinedButton(onClick = { typing = true }, modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Edit")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { continueToSplit() }, modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn)
                ) { Text("Continue · ${chunks.size} task${if (chunks.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.height(8.dp))
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { recognizer?.cancel(); listening = false; typing = true }) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Type")
                    }
                    Box(Modifier.size(84.dp).background(c.accent.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
                        FloatingActionButton(
                            onClick = { if (listening) recognizer?.stopListening() else speak() },
                            containerColor = c.accent, contentColor = c.accentOn, shape = CircleShape, modifier = Modifier.size(64.dp)
                        ) { Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = "Mic") }
                    }
                    Spacer(Modifier.width(88.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ChunkPreview(chunks: List<String>, modifier: Modifier = Modifier) {
    val c = LocalKnock.current
    if (chunks.isEmpty()) return
    Column(modifier) {
        Text("Will become ${chunks.size} task${if (chunks.size == 1) "" else "s"}:", color = c.secondary,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            chunks.forEach { ch ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(c.accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(ch, color = c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
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
