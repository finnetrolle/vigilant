package io.vigilant.gateway.identity

import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Owns one 32-byte process-local secret and derives keys without retaining input tokens.
 *
 * @param randomBytes cryptographic startup randomness; deterministic bytes are a pure test seam.
 */
internal class ExternalIdentityCacheKeyHasher(
    randomBytes: (ByteArray) -> Unit = SecureRandom()::nextBytes
) {
    private val secret = SecretKeySpec(ByteArray(SECRET_BYTES).also(randomBytes), ALGORITHM)

    /** Derives a full content-equal key without retaining the input token. */
    fun keyFor(token: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(secret)
        return HexFormat.of().formatHex(mac.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val SECRET_BYTES = 32
        const val ALGORITHM = "HmacSHA256"
    }
}
