package app.offlinevoice.callprobe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure PCM16 audio helpers, kept free of Android classes so they run in host unit tests. */
object Audio {
    fun bytesToShorts(bytes: ByteArray, count: Int = bytes.size): ShortArray {
        val out = ShortArray(count / 2)
        ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    /** Converts generated speech, nominally -1 to 1, to PCM16. Clips rather than wraps. */
    fun floatsToPcm(samples: FloatArray): ByteArray {
        val output = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) output.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return output.array()
    }

    /**
     * Cleans caller audio before speech recognition: strips the DC offset and low-frequency hum/rumble
     * a phone line carries, then lifts a quiet call to a usable level. Done in place on a copy.
     *
     * Not a noise suppressor — Parakeet is trained on telephone-band speech and handles steady noise
     * well. This only removes the two things that actually hurt it: a DC/rumble baseline, and audio so
     * quiet the recogniser under-reads it.
     */
    // ponytail: one-pole high-pass + peak normalise. Add spectral noise gating only if real calls show
    // the ASR failing on noise this doesn't fix.
    fun cleanForAsr(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        // One-pole high-pass, ~80 Hz at 16 kHz. Removes DC and rumble, keeps all of the speech band.
        val alpha = 0.985f
        var prevIn = samples[0]
        var prevOut = 0f
        var peak = 0f
        for (i in samples.indices) {
            val x = samples[i]
            val y = alpha * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            out[i] = y
            val mag = if (y < 0f) -y else y
            if (mag > peak) peak = mag
        }
        // Peak-normalise to 0.9 if the call is quiet, but never amplify near-silence into noise.
        if (peak in 0.02f..0.9f) {
            val gain = 0.9f / peak
            for (i in out.indices) out[i] *= gain
        }
        return out
    }

    /** The left channel (the caller) of a stereo PCM16 WAV recording, as floats. */
    fun leftChannel(wav: ByteArray): FloatArray {
        val frames = ((wav.size - 44) / 4).coerceAtLeast(0)
        return FloatArray(frames) {
            val o = 44 + it * 4
            ((wav[o + 1].toInt() shl 8) or (wav[o].toInt() and 0xff)).toShort() / 32768f
        }
    }

    /** Linear resample of float samples from [fromRate] to [toRate]. Fine for speech. */
    // ponytail: linear interpolation, no anti-alias. Good enough since the call band-limits anyway.
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val outLength = (samples.size.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(outLength)
        val step = fromRate.toDouble() / toRate
        for (i in out.indices) {
            val pos = i * step
            val index = pos.toInt()
            val frac = (pos - index).toFloat()
            val a = samples[index]
            val b = if (index + 1 < samples.size) samples[index + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /** Halves the sample rate of PCM16 audio by averaging pairs. */
    // ponytail: no anti-alias filter. Fine here because the models already output narrowband speech.
    fun halveRate(pcm: ByteArray): ByteArray {
        val input = bytesToShorts(pcm)
        val output = ByteBuffer.allocate(input.size / 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until input.size / 2) output.putShort(((input[2 * i] + input[2 * i + 1]) / 2).toShort())
        return output.array()
    }

    /** Standard 44-byte header for PCM16. */
    fun wavHeader(dataBytes: Int, sampleRate: Int, channels: Int = 1): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(sampleRate * 2 * channels); putShort((2 * channels).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }.array()

    /** Two mono PCM16 tracks into one stereo track. The shorter one is padded with silence. */
    fun interleave(left: ByteArray, right: ByteArray): ByteArray {
        val frames = maxOf(left.size, right.size) / 2
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            if (2 * i + 1 < left.size) {
                out[4 * i] = left[2 * i]
                out[4 * i + 1] = left[2 * i + 1]
            }
            if (2 * i + 1 < right.size) {
                out[4 * i + 2] = right[2 * i]
                out[4 * i + 3] = right[2 * i + 1]
            }
        }
        return out
    }
}
