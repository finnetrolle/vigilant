package io.vigilant.gateway.identity

import java.net.InetAddress

/**
 * Parsed literal IP network used to check the immediate transport peer without DNS.
 *
 * @param networkBytes canonical binary network address with host bits cleared.
 * @param prefixBits number of leading address bits belonging to the network.
 */
@ConsistentCopyVisibility
data class TrustedNetwork private constructor(
    private val networkBytes: List<Byte>,
    private val prefixBits: Int,
) {
    /** Returns whether [candidate] belongs to this IPv4 or IPv6 network. */
    @Suppress("ReturnCount")
    fun contains(candidate: InetAddress): Boolean {
        val candidateBytes = candidate.address
        if (candidateBytes.size != networkBytes.size) return false
        val completeBytes = prefixBits / Byte.SIZE_BITS
        for (index in 0 until completeBytes) {
            if (candidateBytes[index] != networkBytes[index]) return false
        }
        val remainingBits = prefixBits % Byte.SIZE_BITS
        if (remainingBits == 0) return true
        val mask = FULL_BYTE_MASK shl (Byte.SIZE_BITS - remainingBits) and FULL_BYTE_MASK
        return candidateBytes[completeBytes].toInt() and mask ==
            networkBytes[completeBytes].toInt() and mask
    }

    /** Parses validated literal CIDRs into canonical binary network values. */
    companion object {
        /** Unsigned mask containing every bit in one address byte. */
        private const val FULL_BYTE_MASK = 0xFF

        /**
         * Parses one literal `address/prefix` value and clears host bits.
         *
         * @return canonical network value, or `null` for every unsafe form.
         */
        @Suppress("ReturnCount")
        fun parseOrNull(raw: String): TrustedNetwork? {
            if (raw != raw.trim()) return null
            val separator = raw.lastIndexOf('/')
            if (separator <= 0 || separator == raw.lastIndex) return null
            val address = try {
                InetAddress.ofLiteral(raw.substring(0, separator))
            } catch (_: IllegalArgumentException) {
                return null
            }
            val prefix = raw.substring(separator + 1).toIntOrNull() ?: return null
            val addressBytes = address.address
            if (prefix !in 0..(addressBytes.size * Byte.SIZE_BITS)) return null
            val networkBytes = addressBytes.copyOf()
            val completeBytes = prefix / Byte.SIZE_BITS
            val remainingBits = prefix % Byte.SIZE_BITS
            if (remainingBits > 0) {
                val mask = FULL_BYTE_MASK shl (Byte.SIZE_BITS - remainingBits) and FULL_BYTE_MASK
                networkBytes[completeBytes] = (networkBytes[completeBytes].toInt() and mask).toByte()
            }
            val firstHostByte = completeBytes + if (remainingBits > 0) 1 else 0
            for (index in firstHostByte until networkBytes.size) networkBytes[index] = 0
            return TrustedNetwork(networkBytes.toList(), prefix)
        }
    }
}
