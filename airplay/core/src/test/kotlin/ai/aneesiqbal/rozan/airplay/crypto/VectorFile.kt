package ai.aneesiqbal.rozan.airplay.crypto

private object VectorFileAnchor

/** Reads a `key=value` test-vector file from `src/test/resources/crypto/`; `#` starts a comment line. */
fun vectorFile(name: String): Map<String, String> {
    val stream = checkNotNull(VectorFileAnchor::class.java.getResourceAsStream("/crypto/$name")) { "missing $name" }
    return stream.bufferedReader(Charsets.US_ASCII).useLines { lines ->
        lines
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
