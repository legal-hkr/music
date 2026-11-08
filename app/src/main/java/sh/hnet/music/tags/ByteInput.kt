package sh.hnet.music.tags

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** The file isn't what its header claims, or a size points outside it. */
class TagFormatException(message: String) : IOException(message)

/** Random access to a file's bytes: a file descriptor on the device, an array in tests. */
interface RandomAccessSource {
    val size: Long

    /** Reads up to [length] bytes at [position]; returns the number read, or -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }
}

/** Positional reads, so the channel's own position is never touched. */
class FileChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val size: Long = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length), position)
}

/** Sequential reading; implemented over a file ([SourceReader]) and over an Ogg packet. */
interface ByteInput {
    /** One byte as 0..255; throws [EOFException] at the end. */
    fun readByte(): Int
    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    fun skip(count: Long)
}

fun ByteInput.readBytes(count: Int): ByteArray {
    if (count < 0) throw TagFormatException("Negative length")
    return ByteArray(count).also { readFully(it) }
}

fun ByteInput.u8(): Int = readByte()
fun ByteInput.u16be(): Int = (readByte() shl 8) or readByte()
fun ByteInput.u24be(): Int = (readByte() shl 16) or (readByte() shl 8) or readByte()
fun ByteInput.u32be(): Long = (u16be().toLong() shl 16) or u16be().toLong()
fun ByteInput.u64be(): Long = (u32be() shl 32) or u32be()
fun ByteInput.u16le(): Int = readByte() or (readByte() shl 8)
fun ByteInput.i16le(): Int = u16le().toShort().toInt()
fun ByteInput.u32le(): Long = u16le().toLong() or (u16le().toLong() shl 16)
fun ByteInput.u64le(): Long = u32le() or (u32le() shl 32)
fun ByteInput.latin1(count: Int): String = String(readBytes(count), Charsets.ISO_8859_1)

/** Buffered reader over a [RandomAccessSource] that can seek anywhere. */
class SourceReader(private val source: RandomAccessSource, start: Long = 0) : ByteInput {
    private val buffer = ByteArray(8192)
    private var bufferStart = 0L
    private var bufferLength = 0

    var position: Long = start
        private set
    val size: Long get() = source.size

    fun seek(newPosition: Long) {
        if (newPosition < 0 || newPosition > size) throw TagFormatException("Seek outside the file")
        position = newPosition
    }

    private fun ensureBuffered() {
        if (position >= bufferStart && position < bufferStart + bufferLength) return
        bufferStart = position
        bufferLength = 0
        while (bufferLength < buffer.size) {
            val read = source.read(bufferStart + bufferLength, buffer, bufferLength, buffer.size - bufferLength)
            if (read <= 0) break
            bufferLength += read
        }
        if (bufferLength == 0) throw EOFException()
    }

    override fun readByte(): Int {
        ensureBuffered()
        val value = buffer[(position - bufferStart).toInt()].toInt() and 0xFF
        position++
        return value
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            ensureBuffered()
            val available = (bufferStart + bufferLength - position).toInt()
            val count = minOf(available, length - done)
            System.arraycopy(this.buffer, (position - bufferStart).toInt(), buffer, offset + done, count)
            position += count
            done += count
        }
    }

    override fun skip(count: Long) {
        if (count < 0 || position + count > size) throw EOFException()
        position += count
    }

    /** Bytes at [at] without moving; fewer at the end of the file. */
    fun peek(at: Long, count: Int): ByteArray {
        val saved = position
        val available = (size - at).coerceIn(0, count.toLong()).toInt()
        seek(at)
        val bytes = readBytes(available)
        position = saved
        return bytes
    }
}

internal fun ByteArray.startsWith(prefix: String, at: Int = 0): Boolean =
    size >= at + prefix.length && prefix.indices.all { this[at + it].toInt() and 0xFF == prefix[it].code }
