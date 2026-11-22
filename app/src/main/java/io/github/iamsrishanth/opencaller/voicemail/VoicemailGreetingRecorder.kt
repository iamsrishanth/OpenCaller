package io.github.iamsrishanth.opencaller.voicemail

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/** Records a private PCM greeting and appends the caller-facing voicemail tone. */
class VoicemailGreetingRecorder(
    private val context: Context,
    private val directory: File,
    private val callback: (File?) -> Unit,
) {
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    private val stopRequested = AtomicBoolean(false)

    val isRecording: Boolean get() = worker?.isAlive == true && !stopRequested.get()

    @Synchronized
    fun start(): Boolean {
        if (worker?.isAlive == true) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return false

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, SAMPLE_RATE),
            )
        }.getOrNull() ?: return false
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        val temporary = File(directory, "greeting-recording.tmp.wav")
        val destination = File(directory, GREETING_FILE_NAME)
        stopRequested.set(false)
        try {
            recorder.startRecording()
        } catch (_: Exception) {
            recorder.release()
            temporary.delete()
            return false
        }
        audioRecord = recorder
        worker = Thread({
            val saved = try {
                writeRecording(recorder, temporary, destination)
            } catch (_: Exception) {
                temporary.delete()
                null
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                synchronized(this) {
                    if (audioRecord === recorder) audioRecord = null
                    worker = null
                }
            }
            callback(saved)
        }, "OpenCallerGreetingRecorder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        runCatching { audioRecord?.stop() }
    }

    private fun writeRecording(recorder: AudioRecord, temporary: File, destination: File): File? {
        var pcmBytes = 0
        RandomAccessFile(temporary, "rw").use { output ->
            output.setLength(0L)
            output.write(ByteArray(WAV_HEADER_BYTES))
            val buffer = ByteArray(maxOf(SAMPLE_RATE, AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )))
            while (!stopRequested.get()) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    output.write(buffer, 0, count)
                    pcmBytes += count
                } else if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION) {
                    break
                }
            }
            // Avoid replacing a greeting with an accidental tap that captured no useful audio.
            if (pcmBytes < SAMPLE_RATE) return null.also { temporary.delete() }
            appendTone(output)
            val finalLength = output.length() - WAV_HEADER_BYTES
            output.seek(0L)
            writeWavHeader(output, finalLength.toInt())
            output.fd.sync()
        }
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            temporary.delete()
            return null
        }
        return destination
    }

    private fun appendTone(output: RandomAccessFile) {
        val frames = (SAMPLE_RATE * TONE_SECONDS).toInt()
        for (frame in 0 until frames) {
            val envelope = minOf(1.0, frame / 160.0, (frames - frame) / 160.0).coerceAtLeast(0.0)
            val sample = (sin(2.0 * PI * TONE_HZ * frame / SAMPLE_RATE) * envelope * Short.MAX_VALUE * 0.45)
                .toInt().toShort().toInt()
            output.write(sample and 0xff)
            output.write((sample ushr 8) and 0xff)
        }
    }

    private fun writeWavHeader(output: RandomAccessFile, pcmLength: Int) {
        fun littleEndian(value: Int, bytes: Int) {
            repeat(bytes) { index -> output.write((value ushr (index * 8)) and 0xff) }
        }
        output.writeBytes("RIFF")
        littleEndian(36 + pcmLength, 4)
        output.writeBytes("WAVEfmt ")
        littleEndian(16, 4)
        littleEndian(1, 2)
        littleEndian(1, 2)
        littleEndian(SAMPLE_RATE, 4)
        littleEndian(SAMPLE_RATE * 2, 4)
        littleEndian(2, 2)
        littleEndian(16, 2)
        output.writeBytes("data")
        littleEndian(pcmLength, 4)
    }

    companion object {
        const val GREETING_FILE_NAME = "greeting.wav"
        const val SAMPLE_RATE = 16_000
        private const val WAV_HEADER_BYTES = 44
        private const val TONE_HZ = 880.0
        private const val TONE_SECONDS = 0.35
    }
}
