/*
 * Based on Apple's ALAC reference decoder (ALACDecoder, ag_dec, dp_dec, matrix_dec):
 * Copyright (c) 2011 Apple Inc. All rights reserved. Licensed under the Apache License, Version 2.0;
 * this is a Kotlin reimplementation of its algorithm, not a copy of its code.
 */

package sh.hnet.music.playback

import java.nio.ByteBuffer

/**
 * Apple Lossless (ALAC), decoded by Music itself (owner's decision 27.11.2026, GH #8): Android has no ALAC
 * decoder of its own – only some makers add one – and without one Media3 plays the file as silence. Follows
 * Apple's reference decoder (Apache License 2.0, listed in Settings › About › Licenses), pure Kotlin,
 * JVM-tested bit for bit against ffmpeg's decoder.
 *
 * A frame holds up to [AlacConfig.frameLength] samples per channel as elements: SCE (one channel), CPE (a
 * pair, coded as mid and side), LFE, and FIL / DSE (skipped), ending with END. Each channel is coded by an
 * adaptive linear predictor whose residuals are adaptive Rice codes; the low bytes of 24- and 32-bit samples
 * may be stored apart, uncompressed ("bytes shifted"); a frame the encoder couldn't shrink is stored as is.
 */
class AlacConfig private constructor(
    val frameLength: Int,
    val bitDepth: Int,
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val channels: Int,
    val maxRun: Int,
    val sampleRate: Int,
) {
    /**
     * The channels the decoder outputs: as many as the stream has, but 7 for four – ALAC's 4.0 (C L R Cs) has no
     * place in Android's four (FL FR BL BR), so it goes out as 5.1 + back centre with three silent channels.
     */
    val outputChannels: Int get() = if (channels == 4) 7 else channels

    /** Bytes per sample of the decoder's output: 16 → 2, 20 and 24 → 3 (20-bit samples moved to the top), 32 → 4. */
    val outputBytes: Int get() = when (bitDepth) {
        16 -> 2
        20, 24 -> 3
        else -> 4
    }

    companion object {
        private const val SIZE = 24

        /**
         * The ALACSpecificConfig ("magic cookie"): bare, after the 'alac' box's version and flags, or inside the
         * 'alac' box (and a 'frma' box before it), as containers hand it on.
         */
        fun parse(cookie: ByteArray): AlacConfig {
            var at = 0
            if (cookie.size >= at + 12 && cookie.isType(at + 4, "frma")) at += 12
            if (cookie.size >= at + 12 && cookie.isType(at + 4, "alac")) at += 12
            else if (cookie.size - at == SIZE + 4) at += 4
            if (cookie.size - at < SIZE) throw AlacException("Config too short")
            fun u8(i: Int) = cookie[at + i].toInt() and 0xff
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
            val config = AlacConfig(
                frameLength = u32(0),
                bitDepth = u8(5),
                pb = u8(6),
                mb = u8(7),
                kb = u8(8),
                channels = u8(9),
                maxRun = u16(10),
                sampleRate = u32(20),
            )
            if (u8(4) != 0) throw AlacException("Unknown version")
            if (config.bitDepth !in setOf(16, 20, 24, 32)) throw AlacException("Bit depth ${config.bitDepth}")
            if (config.channels !in 1..MAX_CHANNELS) throw AlacException("Channels ${config.channels}")
            if (config.frameLength !in 1..MAX_FRAME_LENGTH) throw AlacException("Frame length ${config.frameLength}")
            // kb 0: a stream of uncompressed frames only (ffmpeg's lowest compression level writes pb, mb and kb as 0)
            if (config.kb !in 0..MAX_KB) throw AlacException("kb ${config.kb}")
            if (config.sampleRate !in 1..MAX_SAMPLE_RATE) throw AlacException("Sample rate ${config.sampleRate}")
            return config
        }

        private fun ByteArray.isType(at: Int, type: String) = (0 until 4).all { this[at + it].toInt() == type[it].code }

        const val MAX_CHANNELS = 8

        /** The format's frames are 4096 samples; room for others, bounded so a damaged file can't ask for much memory. */
        const val MAX_FRAME_LENGTH = 1 shl 14

        /** The largest Rice parameter the reference allows (the format's encoders use 14). */
        private const val MAX_KB = 23

        private const val MAX_SAMPLE_RATE = 1_536_000
    }
}

class AlacException(message: String) : Exception(message)

/** Decodes the frames of one stream; not thread-safe, one per decoder thread. */
class AlacDecoder(private val config: AlacConfig) {
    private val length = config.frameLength
    private val predictor = IntArray(length)
    private val mixU = IntArray(length)
    private val mixV = IntArray(length)
    private val shiftU = IntArray(length)
    private val shiftV = IntArray(length)
    private val coefsU = IntArray(MAX_COEFS)
    private val coefsV = IntArray(MAX_COEFS)

    /** The decoded samples, channel by channel in the stream's order. */
    private val samples = Array(config.channels) { IntArray(length) }

    /** Where each of Android's channels comes from in the stream (see [ORDER]); -1 a silent one. */
    private val order = ORDER[config.channels - 1]

    private val scratch = ByteArray(length * config.outputChannels * config.outputBytes)

    /**
     * Decodes one frame from [input] into [output] (interleaved little-endian PCM, [AlacConfig.outputBytes] per
     * sample, [AlacConfig.outputChannels] in Android's order) and returns the samples per channel. Throws [AlacException] for a damaged frame.
     */
    fun decode(input: ByteArray, offset: Int, size: Int, output: ByteBuffer): Int {
        if (offset < 0 || size < 0 || offset > input.size - size) throw AlacException("Frame outside its buffer")
        val bits = BitReader(input, offset, size)
        var channel = 0
        var frameSamples = -1
        while (channel < config.channels) {
            when (bits.read(3)) {
                ID_SCE, ID_LFE -> {
                    val n = single(bits, samples[channel])
                    frameSamples = agree(frameSamples, n)
                    channel++
                }
                ID_CPE -> {
                    if (channel + 2 > config.channels) throw AlacException("Pair past the last channel")
                    val n = pair(bits, samples[channel], samples[channel + 1])
                    frameSamples = agree(frameSamples, n)
                    channel += 2
                }
                ID_DSE -> {
                    bits.skip(4)
                    val aligned = bits.read(1) == 1
                    var count = bits.read(8)
                    if (count == 255) count += bits.read(8)
                    if (aligned) bits.align()
                    bits.skip(count * 8)
                }
                ID_FIL -> {
                    var count = bits.read(4)
                    if (count == 15) count += bits.read(8) - 1
                    bits.skip(count * 8)
                }
                ID_END -> break
                else -> throw AlacException("Unsupported element")
            }
        }
        if (channel != config.channels || frameSamples < 0) throw AlacException("Frame ended early")
        write(output, frameSamples)
        return frameSamples
    }

    private fun agree(before: Int, now: Int): Int {
        if (before >= 0 && before != now) throw AlacException("Elements of different lengths")
        return now
    }

    /** The element header common to SCE and CPE: its sample count, bytes shifted and whether it is stored as is. */
    private class Header(val samples: Int, val bytesShifted: Int, val escape: Boolean)

    private fun header(bits: BitReader): Header {
        bits.skip(4) // element instance tag
        if (bits.read(12) != 0) throw AlacException("Reserved header bits")
        val flags = bits.read(4)
        val partial = flags shr 3 != 0
        val bytesShifted = (flags shr 1) and 3
        if (bytesShifted == 3) throw AlacException("Bytes shifted")
        val samples = if (partial) bits.read(32) else length
        if (samples !in 1..length) throw AlacException("Samples $samples")
        return Header(samples, bytesShifted, flags and 1 != 0)
    }

    /** One channel (SCE, LFE). */
    private fun single(bits: BitReader, out: IntArray): Int {
        val h = header(bits)
        val n = h.samples
        var shift = h.bytesShifted * 8
        if (!h.escape) {
            val chanBits = config.bitDepth - shift
            if (chanBits !in 1..32) throw AlacException("Sample width $chanBits")
            bits.skip(16) // mix bits and residue: nothing to mix in one channel
            val u = predictorHeader(bits, coefsU)
            val shiftAt = bits.position
            bits.skip(shift * n)
            residuals(bits, u.pbFactor, n, chanBits)
            unpredict(u, coefsU, n, chanBits, mixU)
            if (shift != 0) {
                val after = bits.position
                bits.position = shiftAt
                for (i in 0 until n) shiftU[i] = bits.read(shift)
                bits.position = after
            }
        } else {
            verbatim(bits, n, config.bitDepth, mixU, null)
            shift = 0
        }
        for (i in 0 until n) out[i] = if (shift != 0) (mixU[i] shl shift) or shiftU[i] else mixU[i]
        return n
    }

    /** A pair (CPE): mid and side, unmixed into left and right. */
    private fun pair(bits: BitReader, left: IntArray, right: IntArray): Int {
        val h = header(bits)
        val n = h.samples
        var shift = h.bytesShifted * 8
        val mixBits: Int
        val mixRes: Int
        if (!h.escape) {
            // The side channel needs one bit more: 32-bit pairs always shift (33 bits is too many, Apple's encoder says)
            val chanBits = config.bitDepth - shift + 1
            if (chanBits !in 1..32) throw AlacException("Sample width $chanBits")
            mixBits = bits.read(8)
            mixRes = bits.read(8).toByte().toInt()
            val u = predictorHeader(bits, coefsU)
            val v = predictorHeader(bits, coefsV)
            val shiftAt = bits.position
            bits.skip(shift * n * 2)
            residuals(bits, u.pbFactor, n, chanBits)
            unpredict(u, coefsU, n, chanBits, mixU)
            residuals(bits, v.pbFactor, n, chanBits)
            unpredict(v, coefsV, n, chanBits, mixV)
            if (shift != 0) {
                val after = bits.position
                bits.position = shiftAt
                for (i in 0 until n) {
                    shiftU[i] = bits.read(shift)
                    shiftV[i] = bits.read(shift)
                }
                bits.position = after
            }
        } else {
            verbatim(bits, n, config.bitDepth, mixU, mixV)
            mixBits = 0
            mixRes = 0
            shift = 0
        }
        for (i in 0 until n) {
            val u = mixU[i]
            val v = mixV[i]
            var l: Int
            var r: Int
            if (mixRes != 0) {
                l = u + v - ((mixRes * v) shr mixBits)
                r = l - v
            } else {
                l = u
                r = v
            }
            if (shift != 0) {
                l = (l shl shift) or shiftU[i]
                r = (r shl shift) or shiftV[i]
            }
            left[i] = l
            right[i] = r
        }
        return n
    }

    private class Predictor(val mode: Int, val denShift: Int, val pbFactor: Int, val order: Int)

    private fun predictorHeader(bits: BitReader, coefs: IntArray): Predictor {
        val first = bits.read(8)
        val second = bits.read(8)
        val order = second and 0x1f
        for (i in 0 until order) coefs[i] = bits.read(16).toShort().toInt()
        val denShift = first and 0xf
        // No rounding is defined for it (the reference shifts by -1); no encoder writes it
        if (denShift == 0 && order in 1..30) throw AlacException("Prediction without a shift")
        return Predictor(first shr 4, denShift, second shr 5, order)
    }

    /** A frame stored as is: [bitDepth]-bit samples, interleaved when there are two channels. */
    private fun verbatim(bits: BitReader, n: Int, bitDepth: Int, u: IntArray, v: IntArray?) {
        val shift = 32 - bitDepth
        for (i in 0 until n) {
            u[i] = (bits.read(bitDepth) shl shift) shr shift
            if (v != null) v[i] = (bits.read(bitDepth) shl shift) shr shift
        }
    }

    /**
     * The adaptive Rice residuals of one channel into [predictor]: the Rice parameter follows a running mean of the
     * values ([mean], scaled by 2^9); when the mean drops low, a run of zeros follows, its length coded the same way.
     * Follows Apple's reference where ffmpeg's decoder differs in corners no encoder reaches in practice (the clamp
     * tested on the value without the zero-run flag, run k 8 for a zero mean, no flag after a run of 65535).
     */
    private fun residuals(bits: BitReader, pbFactor: Int, n: Int, chanBits: Int) {
        val pb = (config.pb * pbFactor) / 4
        val kb = config.kb
        if (kb == 0) throw AlacException("Compressed frame in a stream without Rice parameters")
        val wb = (1 shl kb) - 1
        var mean = config.mb
        var zero = 0
        var c = 0
        while (c < n) {
            var k = 31 - Integer.numberOfLeadingZeros((mean ushr QB_SHIFT) + 3)
            if (k > kb) k = kb
            val value = rice(bits, (1 shl k) - 1, k, chanBits, MAX_PREFIX)
            val coded = value + zero
            predictor[c++] = if (coded and 1 != 0) -((coded ushr 1) + 1) else coded ushr 1
            mean = pb * coded + mean - ((pb * mean) ushr QB_SHIFT)
            if (Integer.compareUnsigned(value, MEAN_CLAMP) > 0) mean = MEAN_CLAMP
            zero = 0
            if (Integer.compareUnsigned(mean shl 2, QB) < 0 && c < n) {
                zero = 1
                val kz = Integer.numberOfLeadingZeros(mean) - 24 + ((mean + 16) ushr 6)
                val run = rice(bits, ((1 shl kz) - 1) and wb, kz, RUN_BITS, MAX_PREFIX)
                if (run > n - c) throw AlacException("Zero run past the frame")
                repeat(run) { predictor[c++] = 0 }
                if (run >= 65535) zero = 0
                mean = 0
            }
        }
    }

    /**
     * One Rice code: a unary prefix of ones (at most [maxPrefix]; that many means [escapeBits] bits follow as the
     * value), then k bits, of which only k − 1 are used when their value is below 2.
     */
    private fun rice(bits: BitReader, m: Int, k: Int, escapeBits: Int, maxPrefix: Int): Int {
        // The prefix: leading ones of the next bits (at most maxPrefix), then the zero ending it
        val window = minOf(maxPrefix, bits.left())
        val ones = Integer.numberOfLeadingZeros((bits.peek(window) shl (32 - window)).inv())
        val prefix = minOf(ones, window)
        if (prefix >= maxPrefix) {
            bits.skip(maxPrefix)
            return bits.read(escapeBits)
        }
        bits.skip(prefix + 1)
        if (k == 1) return prefix
        val v = bits.peek(k)
        return if (v >= 2) {
            bits.skip(k)
            prefix * m + v - 1
        } else {
            bits.skip(k - 1)
            prefix * m
        }
    }

    /** Undoes the prediction: mode 15 is a first-order pass before the coded one. */
    private fun unpredict(p: Predictor, coefs: IntArray, n: Int, chanBits: Int, out: IntArray) {
        when (p.mode) {
            0 -> lpc(predictor, out, n, coefs, p.order, chanBits, p.denShift)
            15 -> {
                lpc(predictor, predictor, n, coefs, 31, chanBits, 0)
                lpc(predictor, out, n, coefs, p.order, chanBits, p.denShift)
            }
            else -> throw AlacException("Prediction mode ${p.mode}")
        }
    }

    /**
     * The adaptive linear predictor: each sample is its residual plus the prediction from the [order] samples
     * before it (relative to the one before those), and the coefficients move a step after each sample in the
     * direction that would have shrunk the residual. Order 31 is plain first-order prediction.
     */
    private fun lpc(input: IntArray, out: IntArray, n: Int, coefs: IntArray, order: Int, chanBits: Int, denShift: Int) {
        val chanShift = 32 - chanBits
        out[0] = input[0]
        if (order == 0) {
            if (input !== out) System.arraycopy(input, 1, out, 1, n - 1)
            return
        }
        if (order == 31) {
            var previous = out[0]
            for (j in 1 until n) {
                previous = ((input[j] + previous) shl chanShift) shr chanShift
                out[j] = previous
            }
            return
        }
        val warm = minOf(order, n - 1)
        for (j in 1..warm) out[j] = ((input[j] + out[j - 1]) shl chanShift) shr chanShift
        val half = if (denShift == 0) 0 else 1 shl (denShift - 1)
        val lim = order + 1
        for (j in lim until n) {
            val top = out[j - lim]
            var sum = 0
            for (k in 0 until order) sum += coefs[k] * (out[j - 1 - k] - top)
            var residual = input[j]
            val sign = Integer.signum(residual)
            out[j] = ((residual + top + ((sum + half) shr denShift)) shl chanShift) shr chanShift
            if (sign > 0) {
                for (k in order - 1 downTo 0) {
                    val d = top - out[j - 1 - k]
                    val s = Integer.signum(d)
                    coefs[k] = (coefs[k] - s).toShort().toInt()
                    residual -= (order - k) * ((s * d) shr denShift)
                    if (residual <= 0) break
                }
            } else if (sign < 0) {
                for (k in order - 1 downTo 0) {
                    val d = top - out[j - 1 - k]
                    val s = Integer.signum(d)
                    coefs[k] = (coefs[k] + s).toShort().toInt()
                    residual -= (order - k) * ((-s * d) shr denShift)
                    if (residual >= 0) break
                }
            }
        }
    }

    /** The frame interleaved in Android's order, little-endian, into [scratch] and then the output in one go. */
    private fun write(output: ByteBuffer, n: Int) {
        val channels = config.outputChannels
        val bytes = config.outputBytes
        val up = if (config.bitDepth == 20) 4 else 0
        var at = 0
        for (i in 0 until n) for (c in 0 until channels) {
            val source = order[c]
            val v = if (source < 0) 0 else samples[source][i] shl up
            scratch[at] = v.toByte()
            scratch[at + 1] = (v shr 8).toByte()
            if (bytes >= 3) scratch[at + 2] = (v shr 16).toByte()
            if (bytes == 4) scratch[at + 3] = (v shr 24).toByte()
            at += bytes
        }
        output.put(scratch, 0, at)
    }

    /** Big-endian bits of a frame; reading past its end is a damaged frame. */
    private class BitReader(private val data: ByteArray, offset: Int, size: Int) {
        private val start = offset.toLong() * 8
        private val end = (offset.toLong() + size) * 8

        /** In bits from the frame's start. */
        var position: Long = 0

        fun read(count: Int): Int {
            val v = peek(count)
            position += count
            return v
        }

        /** The next [count] bits (0–32) without moving on. */
        fun peek(count: Int): Int {
            if (count == 0) return 0
            var at = start + position
            if (at + count > end) throw AlacException("Past the frame's end")
            var value = 0L
            var left = count
            while (left > 0) {
                val byte = data[(at ushr 3).toInt()].toInt() and 0xff
                val bitInByte = (at and 7).toInt()
                val take = minOf(8 - bitInByte, left)
                val bitsHere = (byte shr (8 - bitInByte - take)) and ((1 shl take) - 1)
                value = (value shl take) or bitsHere.toLong()
                left -= take
                at += take
            }
            return value.toInt()
        }

        fun skip(count: Int) {
            position += count
            if (start + position > end) throw AlacException("Past the frame's end")
        }

        fun align() {
            position = (position + 7) and 7L.inv()
        }

        /** Bits left in the frame, at most Int.MAX_VALUE. */
        fun left(): Int = (end - start - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_LFE = 3
        const val ID_DSE = 4
        const val ID_FIL = 6
        const val ID_END = 7

        const val QB_SHIFT = 9
        const val QB = 1 shl QB_SHIFT
        const val MEAN_CLAMP = 0xffff
        const val MAX_PREFIX = 9
        const val RUN_BITS = 16
        const val MAX_COEFS = 32

        /**
         * Android's channel order (Media3: FL FR FC LFE BL BR BC / SL SR by channel count) from ALAC's default
         * layouts: 3 C L R, 4 C L R Cs, 5 C L R Ls Rs, 6 + LFE, 7 C L R Ls Rs Cs LFE, 8 C Lc Rc L R Ls Rs LFE.
         * Four (C L R Cs) have no place in Android's four (FL FR BL BR): they go out as seven, FL FR FC LFE BL BR BC,
         * with the LFE and the back pair silent ([AlacConfig.outputChannels]).
         */
        val ORDER = arrayOf(
            intArrayOf(0),
            intArrayOf(0, 1),
            intArrayOf(1, 2, 0),
            intArrayOf(1, 2, 0, -1, -1, -1, 3),
            intArrayOf(1, 2, 0, 3, 4),
            intArrayOf(1, 2, 0, 5, 3, 4),
            intArrayOf(1, 2, 0, 6, 3, 4, 5),
            intArrayOf(3, 4, 0, 7, 5, 6, 1, 2),
        )
    }
}
