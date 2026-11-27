package ai.aneesiqbal.rozan.airplay.rtsp

import java.io.InputStream

/**
 * Reads requests one at a time from a blocking stream, e.g. a socket's input, using [RtspRequestParser].
 *
 * Bytes are read in chunks of up to `bufferSize`; requests that arrive together are queued and returned
 * by later calls without reading again. Not thread-safe.
 *
 * @param input the stream to read; it is not closed by this class.
 * @param limits the size limits applied to every request.
 * @param bufferSize the read chunk size.
 * @param bodyLimit lowers the body limit per request; see [RtspRequestParser].
 */
public class RtspRequestReader(
    input: InputStream,
    limits: RtspLimits = RtspLimits(),
    bufferSize: Int = DEFAULT_READ_BUFFER_BYTES,
    bodyLimit: RtspBodyLimit? = null,
) {
    private val parser = RtspRequestParser(limits, bodyLimit)
    private val pump = StreamPump(input, bufferSize, parser::feed, parser::finish)

    /**
     * Blocks until the next request is complete and returns it, or returns null if the stream ended
     * cleanly between requests.
     *
     * @throws RtspException if the input is malformed, over a limit, or ends in the middle of a request.
     * @throws java.io.IOException if reading fails.
     */
    public fun read(): RtspRequest? = pump.next()
}

/**
 * Reads responses one at a time from a blocking stream using [RtspResponseParser]; the counterpart of
 * [RtspRequestReader] for connections where the receiver sends the requests.
 *
 * @param input the stream to read; it is not closed by this class.
 * @param limits the size limits applied to every response.
 * @param bufferSize the read chunk size.
 */
public class RtspResponseReader(
    input: InputStream,
    limits: RtspLimits = RtspLimits(),
    bufferSize: Int = DEFAULT_READ_BUFFER_BYTES,
) {
    private val parser = RtspResponseParser(limits)
    private val pump = StreamPump(input, bufferSize, parser::feed, parser::finish)

    /**
     * Blocks until the next response is complete and returns it, or returns null if the stream ended
     * cleanly between responses.
     *
     * @throws RtspException if the input is malformed, over a limit, or ends in the middle of a response.
     * @throws java.io.IOException if reading fails.
     */
    public fun read(): RtspResponse? = pump.next()
}

/** Default read chunk size for [RtspRequestReader] and [RtspResponseReader]: 8 KiB. */
public const val DEFAULT_READ_BUFFER_BYTES: Int = 8 * 1024

private class StreamPump<T : Any>(
    private val input: InputStream,
    bufferSize: Int,
    private val feed: (ByteArray, Int, Int) -> List<T>,
    private val finish: () -> Unit,
) {
    init {
        require(bufferSize > 0) { "bufferSize must be positive" }
    }

    private val buffer = ByteArray(bufferSize)
    private val pending = ArrayDeque<T>()
    private var ended = false

    fun next(): T? {
        while (pending.isEmpty() && !ended) {
            val count = input.read(buffer, 0, buffer.size)
            if (count < 0) {
                ended = true
                finish()
            } else {
                pending.addAll(feed(buffer, 0, count))
            }
        }
        return pending.removeFirstOrNull()
    }
}
