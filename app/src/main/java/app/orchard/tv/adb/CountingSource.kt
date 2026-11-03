package app.orchard.tv.adb

import okio.Source
import okio.Timeout

/**
 * Wraps an install/push source so the caller can follow the transfer. Ported from skyadb; the
 * progress callback runs on the thread reading the source (the IO dispatcher).
 */
class CountingSource(
    private val delegate: Source,
    private val total: Long,
    private val onProgress: (transferred: Long, total: Long) -> Unit,
) : Source {
    private var transferred: Long = 0

    override fun read(sink: okio.Buffer, byteCount: Long): Long {
        val read = delegate.read(sink, byteCount)
        if (read != -1L) {
            transferred += read
            onProgress(transferred, total)
        }
        return read
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = delegate.close()
}
