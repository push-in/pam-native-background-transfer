package dev.pam.backgroundtransfer

internal object TransferIdentity {
    private const val PREFIX = "dev.pam.background-transfer.kind."

    fun tag(kind: Int): String {
        require(kind in 1..2) { "Invalid transfer kind" }
        return PREFIX + kind
    }

    fun kind(tags: Set<String>, outputKind: Int): Int {
        val stored = tags.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX).toIntOrNull() }
        if (stored.isNotEmpty()) {
            require(stored.size == 1 && stored[0] in 1..2) { "Invalid transfer identity" }
            return stored[0]!!
        }
        require(outputKind in 1..2) { "Transfer kind is unavailable" }
        return outputKind
    }
}
