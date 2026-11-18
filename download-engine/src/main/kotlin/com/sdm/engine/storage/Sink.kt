package com.sdm.engine.storage

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access output. Positional writes to disjoint ranges are safe from several threads,
 * which is what lets segments write without a lock.
 */
interface RandomAccessSink : Closeable {
    fun write(position: Long, src: ByteArray, offset: Int, length: Int)
    fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int
    /** Flushes written bytes to storage. Called before every checkpoint is recorded. */
    fun sync()
    fun setLength(size: Long)
    fun size(): Long
}

/** Thrown for write-side failures so they are never mistaken for network errors. */
class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

class FileSink(file: File) : RandomAccessSink {
    private val raf = RandomAccessFile(file, "rw")
    private val channel: FileChannel = raf.channel

    override fun write(position: Long, src: ByteArray, offset: Int, length: Int) {
        val buf = ByteBuffer.wrap(src, offset, length)
        var pos = position
        while (buf.hasRemaining()) pos += channel.write(buf, pos)
    }

    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int = channel.read(ByteBuffer.wrap(dst, offset, length), position)
    override fun sync() = channel.force(false)
    override fun setLength(size: Long) = raf.setLength(size)
    override fun size(): Long = channel.size()
    override fun close() = raf.close()
}
