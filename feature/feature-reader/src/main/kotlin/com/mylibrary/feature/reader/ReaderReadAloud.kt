package com.mylibrary.feature.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Read aloud, backed by the platform's text-to-speech engine.
 *
 * The engine is a platform resource with a lifecycle — it must be shut down when the reader leaves,
 * and it initialises asynchronously — so it lives in a small object the reader's screen remembers,
 * never in the ViewModel. All the ViewModel does is decide *what* text to speak (see
 * `ReaderViewModel.readAloud`); this class owns *how*, and reports whether it is speaking so the
 * toolbar can offer the right action.
 */
@Composable
internal fun rememberReaderSpeaker(): ReaderSpeaker {
    val context = LocalContext.current
    val speaker = remember(context) { ReaderSpeaker(context.applicationContext) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}

@Stable
internal class ReaderSpeaker(context: Context) {

    /** Whether the engine is currently speaking — drives the toolbar's start/stop label. */
    var isSpeaking by mutableStateOf(false)
        private set

    private var ready = false
    private var pending: String? = null

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            isSpeaking = true
        }

        override fun onDone(utteranceId: String?) {
            isSpeaking = false
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            isSpeaking = false
        }
    }

    private val engine = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        // A request that arrived before the engine was ready is honoured once, rather than dropped:
        // the first tap on "read aloud" is usually the one that races initialisation.
        pending?.let { text -> if (ready) speak(text) }
        pending = null
    }

    /** Speaks [text], replacing anything currently being spoken. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pending = text
            return
        }
        engine.setOnUtteranceProgressListener(progressListener)
        isSpeaking = true
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Stops speaking, if anything is. */
    fun stop() {
        engine.stop()
        isSpeaking = false
    }

    /** Releases the engine. Called when the reader leaves the screen. */
    fun shutdown() {
        engine.stop()
        engine.shutdown()
        isSpeaking = false
    }

    private companion object {
        const val UTTERANCE_ID = "mylibrary-read-aloud"
    }
}
