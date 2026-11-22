package io.github.iamsrishanth.opencaller.sip

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams caller-only PCM into Android's on-device recognizer through EXTRA_AUDIO_SOURCE.
 * OpenCaller does not create an AudioRecord: caller PCM is passed through a pipe, with no ordinary
 * SpeechRecognizer fallback. The system recognition service must honor EXTRA_AUDIO_SOURCE;
 * Android documents that services which ignore it may open their own microphone instead.
 */
internal class OnDeviceLiveTranscriber(
    context: Context,
    private val onTranscript: (String?) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frames = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_FRAMES)
    private val acceptingFrames = AtomicBoolean(true)
    private val unavailableReported = AtomicBoolean(false)
    private val resultOrError = CountDownLatch(1)
    private val currentText = AtomicReference<String?>(null)
    private val textLock = Any()
    private var finalizedText = ""
    private var partialText = ""
    private var recognizer: SpeechRecognizer? = null
    private var readPipe: ParcelFileDescriptor? = null
    private var writePipe: ParcelFileDescriptor? = null
    private var writerThread: Thread? = null

    /** Returns false only when this platform cannot even attempt safe local transcription. */
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            reportUnavailable()
            return false
        }
        mainHandler.post { startOnMainThread() }
        return true
    }

    /** Nonblocking callback safe to call from the PJSIP conference thread. */
    fun offerPcmFrame(frame: ByteArray) {
        if (!acceptingFrames.get() || frame.isEmpty()) return
        if (!frames.offer(frame)) {
            frames.poll()
            frames.offer(frame)
        }
    }

    /** Ends the PCM stream and gives Android a short opportunity to publish its final segment. */
    fun stopAndCollect(): String? {
        if (acceptingFrames.getAndSet(false)) {
            frames.clear()
            runCatching { writePipe?.close() }
            writePipe = null
            runCatching { writerThread?.join(WRITER_JOIN_MILLIS) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mainHandler.post { runCatching { recognizer?.stopListening() } }
            }
            resultOrError.await(FINAL_RESULT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }
        mainHandler.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            runCatching { readPipe?.close() }
            readPipe = null
        }
        return currentText.get()
    }

    private fun startOnMainThread() {
        if (!acceptingFrames.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            reportUnavailable()
            return
        }

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readPipe = pipe[0]
            writePipe = pipe[1]
            val localRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            recognizer = localRecognizer
            localRecognizer.setRecognitionListener(listener)

            val intent = baseIntent().putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
            localRecognizer.checkRecognitionSupport(
                intent,
                appContext.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val languageIsInstalled = support.installedOnDeviceLanguages.orEmpty().any { tag ->
                            Locale.forLanguageTag(tag).language == Locale.getDefault().language
                        }
                        if (!acceptingFrames.get() || !languageIsInstalled) {
                            reportUnavailable()
                            stopAndClosePipes()
                            return
                        }
                        beginListening(localRecognizer, intent)
                    }

                    override fun onError(error: Int) {
                        reportUnavailable()
                        stopAndClosePipes()
                    }
                },
            )
        } catch (_: Exception) {
            reportUnavailable()
            stopAndClosePipes()
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun beginListening(localRecognizer: SpeechRecognizer, intent: Intent) {
        val writer = writePipe ?: run {
            reportUnavailable()
            return
        }
        writerThread = Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                try {
                    while (acceptingFrames.get() || frames.isNotEmpty()) {
                        val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        output.write(frame)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: IOException) {
                    // A rejected or closed recognition pipe only disables transcript text.
                }
            }
        }, "OpenCaller-TranscriptAudio").apply {
            isDaemon = true
            start()
        }
        try {
            localRecognizer.startListening(intent)
        } catch (_: Exception) {
            reportUnavailable()
            stopAndClosePipes()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            resultOrError.countDown()
            if (currentText.get().isNullOrBlank()) reportUnavailable()
        }

        override fun onResults(results: android.os.Bundle?) {
            appendTranscript(bestText(results), isFinal = true)
            resultOrError.countDown()
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            appendTranscript(bestText(partialResults), isFinal = false)
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private fun appendTranscript(segment: String?, isFinal: Boolean) {
        val normalized = segment?.trim().orEmpty()
        val published = synchronized(textLock) {
            if (isFinal && normalized.isNotEmpty()) finalizedText = listOf(finalizedText, normalized).filter(String::isNotBlank).joinToString(" ")
            partialText = if (isFinal) "" else normalized
            listOf(finalizedText, partialText).filter(String::isNotBlank).joinToString(" ")
                .take(MAX_TRANSCRIPT_CHARS)
                .takeIf(String::isNotBlank)
        }
        currentText.set(published)
        onTranscript(published)
    }

    private fun baseIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
    }

    private fun reportUnavailable() {
        if (unavailableReported.compareAndSet(false, true)) mainHandler.post(onUnavailable)
    }

    private fun stopAndClosePipes() {
        acceptingFrames.set(false)
        frames.clear()
        runCatching { writePipe?.close() }
        writePipe = null
        runCatching { readPipe?.close() }
        readPipe = null
        writerThread?.interrupt()
        resultOrError.countDown()
    }

    private fun bestText(bundle: android.os.Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val MAX_QUEUED_FRAMES = 20
        private const val MAX_TRANSCRIPT_CHARS = 16_000
        private const val WRITER_JOIN_MILLIS = 250L
        private const val FINAL_RESULT_WAIT_MILLIS = 900L
    }
}
