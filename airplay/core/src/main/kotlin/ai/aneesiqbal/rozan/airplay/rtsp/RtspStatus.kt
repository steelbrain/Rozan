package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Status codes used on the control channel and their reason phrases.
 *
 * Codes and phrases come from RFC 2326 (RTSP/1.0) and RFC 9110 (HTTP). Where the two disagree (451, and
 * the wording of 413 and 414), [reasonPhrase] picks by protocol.
 */
public object RtspStatus {
    /** 100 Continue. */
    public const val CONTINUE: Int = 100

    /** 101 Switching Protocols. */
    public const val SWITCHING_PROTOCOLS: Int = 101

    /** 200 OK. */
    public const val OK: Int = 200

    /** 204 No Content (HTTP only; such a response carries no body and no `Content-Length`). */
    public const val NO_CONTENT: Int = 204

    /** 400 Bad Request. */
    public const val BAD_REQUEST: Int = 400

    /** 401 Unauthorized. */
    public const val UNAUTHORIZED: Int = 401

    /** 403 Forbidden. */
    public const val FORBIDDEN: Int = 403

    /** 404 Not Found. */
    public const val NOT_FOUND: Int = 404

    /** 405 Method Not Allowed. */
    public const val METHOD_NOT_ALLOWED: Int = 405

    /** 413: the body is larger than the receiver accepts. */
    public const val CONTENT_TOO_LARGE: Int = 413

    /** 414: the request line is longer than the receiver accepts. */
    public const val URI_TOO_LONG: Int = 414

    /** 415 Unsupported Media Type. */
    public const val UNSUPPORTED_MEDIA_TYPE: Int = 415

    /** 431 Request Header Fields Too Large (RFC 6585). */
    public const val HEADER_FIELDS_TOO_LARGE: Int = 431

    /** 454 Session Not Found (RTSP). */
    public const val SESSION_NOT_FOUND: Int = 454

    /** 455 Method Not Valid in This State (RTSP). */
    public const val METHOD_NOT_VALID_IN_THIS_STATE: Int = 455

    /** 461 Unsupported Transport (RTSP). */
    public const val UNSUPPORTED_TRANSPORT: Int = 461

    /** 500 Internal Server Error. */
    public const val INTERNAL_SERVER_ERROR: Int = 500

    /** 501 Not Implemented. */
    public const val NOT_IMPLEMENTED: Int = 501

    /** 503 Service Unavailable. */
    public const val SERVICE_UNAVAILABLE: Int = 503

    /** 505: the request's protocol version is not supported. */
    public const val VERSION_NOT_SUPPORTED: Int = 505

    private val COMMON: Map<Int, String> =
        mapOf(
            100 to "Continue",
            101 to "Switching Protocols",
            200 to "OK",
            201 to "Created",
            204 to "No Content",
            301 to "Moved Permanently",
            302 to "Found",
            304 to "Not Modified",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            406 to "Not Acceptable",
            408 to "Request Timeout",
            415 to "Unsupported Media Type",
            431 to "Request Header Fields Too Large",
            500 to "Internal Server Error",
            501 to "Not Implemented",
            503 to "Service Unavailable",
        )

    private val RTSP_ONLY: Map<Int, String> =
        mapOf(
            413 to "Request Entity Too Large",
            414 to "Request-URI Too Large",
            451 to "Parameter Not Understood",
            453 to "Not Enough Bandwidth",
            454 to "Session Not Found",
            455 to "Method Not Valid in This State",
            457 to "Invalid Range",
            459 to "Aggregate Operation Not Allowed",
            461 to "Unsupported Transport",
            505 to "RTSP Version Not Supported",
        )

    private val HTTP_ONLY: Map<Int, String> =
        mapOf(
            413 to "Content Too Large",
            414 to "URI Too Long",
            505 to "HTTP Version Not Supported",
        )

    /**
     * Returns the standard reason phrase for [status] under [protocol], or an empty string for codes not
     * listed here (an empty reason phrase is valid on the wire).
     */
    public fun reasonPhrase(
        status: Int,
        protocol: RtspProtocol,
    ): String {
        val specific = if (protocol == RtspProtocol.RTSP_1_0) RTSP_ONLY else HTTP_ONLY
        return specific[status] ?: COMMON[status] ?: ""
    }
}
