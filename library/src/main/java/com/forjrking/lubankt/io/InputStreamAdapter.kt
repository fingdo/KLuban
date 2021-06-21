package com.forjrking.lubankt.io

import com.forjrking.lubankt.Checker.MARK_READ_LIMIT
import java.io.IOException
import java.io.InputStream
import kotlin.jvm.Throws

/**
 * Automatically close the previous InputStream when opening a new InputStream,
 * and finally need to manually call [.close] to release the resource.
 */
abstract class InputStreamAdapter<T> : InputStreamProvider<T> {

    // We don't check is.markSupported() here because RecyclableBufferedInputStream allows resetting
    // after exceeding MARK_READ_LIMIT, which other InputStreams don't guarantee.
    private lateinit var inputStream: BufferedInputStreamWrap

    @Throws(IOException::class)
    abstract fun openInternal(): InputStream

    abstract fun fileSizeInternal(): Long

    @Throws(IOException::class)
    override fun rewindAndGet(): InputStream {
        if (::inputStream.isInitialized) {
            inputStream.reset()
        } else {
            inputStream = BufferedInputStreamWrap(openInternal())
//            inputStream.mark(MARK_READ_LIMIT)
        }
        return inputStream
    }

    override fun getFileSize(): Long {
        return fileSizeInternal()
    }

    override fun close() {
        if (::inputStream.isInitialized) {
            try {
                inputStream.close()
            } catch (ignore: IOException) {
                ignore.printStackTrace()
            }
        }
    }
}