package io.github.iamsrishanth.opencaller.sip

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Selects the settings-owned greeting or synthesizes a private PCM greeting with a short tone. */
internal object SipDefaultVoicemailGreeting {
    private const val GREETING_TEXT = "Hello. The person you called cannot answer right now. Please leave a message after the tone."
    private const val TARGET_SAMPLE_RATE = 16_000
    private const val TONE_FREQUENCY_HZ = 1_000.0
    private const val TONE_DURATION_MS = 700
    private const val SYNTHESIS_TIMEOUT_MS = 20_000L

    suspend fun select(context: Context): File? {
        val voicemailDirectory = File(context.applicationContext.filesDir, "voicemail")
        if (!voicemailDirectory.mkdirs() && !voicemailDirectory.isDirectory) return null

        val customGreeting = File(voicemailDirectory, "greeting.wav")
        if (customGreeting.isFile && isPcmWave(customGreeting)) return customGreeting

        val generatedGreeting = File(voicemailDirectory, "default-greeting.wav")
        if (generatedGreeting.isFile && isPcmWave(generatedGreeting)) return generatedGreeting

        val synthesizedSpeech = File(voicemailDirectory, "default-greeting-tts.wav")
        synthesizedSpeech.delete()
        val completedSpeech = withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) {
            synthesize(context.applicationContext, synthesizedSpeech)
        } ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val pcm = parsePcmWave(completedSpeech.readBytes()) ?: return@withContext null
                if (pcm.format != WAV_FORMAT_PCM || pcm.channels !in 1..2 || pcm.sampleRate <= 0) {
                    return@withContext null
                }
                val mono = downmixToMono(pcm.samples, pcm.channels)
                val speechAtTargetRate = resample(mono, pcm.sampleRate, TARGET_SAMPLE_RATE)
                val samplesWithTone = speechAtTargetRate + createToneSamples()
                val output = waveBytes(samplesWithTone, TARGET_SAMPLE_RATE)
                FileOutputStream(generatedGreeting).use { it.write(output) }
                generatedGreeting.takeIf(::isPcmWave)
            } catch (_: Exception) {
                generatedGreeting.delete()
                null
            } finally {
                synthesizedSpeech.delete()
            }
        }
    }

    private suspend fun synthesize(context: Context, destination: File): File? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                val utteranceId = "opencaller-default-${UUID.randomUUID()}"
                val finished = AtomicBoolean(false)
                var tts: TextToSpeech? = null

                fun finish(result: File?) {
                    if (!finished.compareAndSet(false, true)) return
                    val engine = tts
                    engine?.shutdown()
                    if (result == null) destination.delete()
                    if (continuation.isActive) continuation.resume(result)
                }

                continuation.invokeOnCancellation {
                    mainHandler.post {
                        if (finished.compareAndSet(false, true)) {
                            tts?.stop()
                            tts?.shutdown()
                            destination.delete()
                        }
                    }
                }

                val engine = runCatching {
                    TextToSpeech(context) { status ->
                        mainHandler.post {
                            if (finished.get()) return@post
                            val readyEngine = tts ?: return@post
                            if (status != TextToSpeech.SUCCESS) {
                                finish(null)
                                return@post
                            }

                            if (readyEngine.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE) {
                                readyEngine.language = Locale.US
                            } else {
                                readyEngine.language = Locale.getDefault()
                            }
                            readyEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) = Unit

                                override fun onDone(id: String?) {
                                    if (id == utteranceId) {
                                        finish(destination.takeIf { it.isFile && it.length() > 44L })
                                    }
                                }

                                override fun onError(id: String?) {
                                    if (id == utteranceId) finish(null)
                                }
                            })

                            val queued = runCatching {
                                readyEngine.synthesizeToFile(GREETING_TEXT, Bundle(), destination, utteranceId)
                            }.getOrDefault(TextToSpeech.ERROR)
                            if (queued != TextToSpeech.SUCCESS) finish(null)
                        }
                    }
                }.getOrNull()
                if (engine == null) {
                    finish(null)
                } else {
                    tts = engine
                }
            }
        }

    private fun isPcmWave(file: File): Boolean {
        if (!file.isFile || file.length() < 44L) return false
        val wave = runCatching { parsePcmWave(file.readBytes()) }.getOrNull() ?: return false
        return wave.format == WAV_FORMAT_PCM && wave.channels in 1..2 && wave.sampleRate > 0
    }

    private fun parsePcmWave(bytes: ByteArray): PcmWave? {
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return null
        var offset = 12
        var channels = 0
        var sampleRate = 0
        var format = 0
        var samples: ShortArray? = null
        while (offset + 8 <= bytes.size) {
            val chunkName = ascii(bytes, offset, 4)
            val chunkSize = littleInt(bytes, offset + 4)
            val dataStart = offset + 8
            if (chunkSize < 0 || dataStart.toLong() + chunkSize > bytes.size) return null
            when (chunkName) {
                "fmt " -> {
                    if (chunkSize < 16) return null
                    format = littleShort(bytes, dataStart)
                    channels = littleShort(bytes, dataStart + 2)
                    sampleRate = littleInt(bytes, dataStart + 4)
                    val bitsPerSample = littleShort(bytes, dataStart + 14)
                    if (bitsPerSample != 16) return null
                }
                "data" -> {
                    if (chunkSize < 2 || chunkSize % 2 != 0) return null
                    val count = chunkSize / 2
                    samples = ShortArray(count) { index -> littleShort(bytes, dataStart + index * 2).toShort() }
                }
            }
            offset = dataStart + chunkSize + (chunkSize and 1)
        }
        val audio = samples ?: return null
        if (format != 1 || channels !in 1..2 || sampleRate <= 0 || audio.size % channels != 0) return null
        return PcmWave(format, channels, sampleRate, audio)
    }

    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels == 1) return samples
        return ShortArray(samples.size / channels) { frame ->
            val left = samples[frame * channels].toInt()
            val right = samples[frame * channels + 1].toInt()
            ((left + right) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun resample(source: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        if (source.isEmpty()) return source
        if (sourceRate == targetRate) return source
        val outputLength = ((source.size.toLong() * targetRate) / sourceRate).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ShortArray(outputLength) { outputIndex ->
            val position = outputIndex.toDouble() * sourceRate / targetRate
            val start = position.toInt().coerceIn(0, source.lastIndex)
            val end = (start + 1).coerceAtMost(source.lastIndex)
            val fraction = position - start
            (source[start] + (source[end] - source[start]) * fraction)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun createToneSamples(): ShortArray {
        val frameCount = TARGET_SAMPLE_RATE * TONE_DURATION_MS / 1_000
        return ShortArray(frameCount) { index ->
            val fadeFrames = TARGET_SAMPLE_RATE / 100
            val edgeGain = minOf(1.0, index.toDouble() / fadeFrames, (frameCount - index).toDouble() / fadeFrames)
            val wave = kotlin.math.sin(2.0 * Math.PI * TONE_FREQUENCY_HZ * index / TARGET_SAMPLE_RATE)
            (wave * edgeGain * Short.MAX_VALUE * 0.55).toInt().toShort()
        }
    }

    private fun waveBytes(samples: ShortArray, sampleRate: Int): ByteArray {
        val pcmSize = samples.size * 2
        val output = ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII))
        output.putInt(36 + pcmSize)
        output.put("WAVE".toByteArray(Charsets.US_ASCII))
        output.put("fmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(1)
        output.putInt(sampleRate)
        output.putInt(sampleRate * 2)
        output.putShort(2)
        output.putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(pcmSize)
        samples.forEach(output::putShort)
        return output.array()
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int) =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun littleShort(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleInt(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private data class PcmWave(
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val samples: ShortArray,
    )

    private const val WAV_FORMAT_PCM = 1
}
