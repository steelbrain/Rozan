package ai.aneesiqbal.rozan.airplay.session

/** Whether this array begins with [prefix]. */
internal fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}
