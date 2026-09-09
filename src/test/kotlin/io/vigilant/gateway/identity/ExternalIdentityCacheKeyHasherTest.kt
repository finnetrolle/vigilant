package io.vigilant.gateway.identity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure key-generation evidence against independent published and OpenSSL-verified vectors. */
class ExternalIdentityCacheKeyHasherTest {
    /**
     * Literal OpenSSL vectors independently detect case folding, truncation, secret reuse, and
     * crypto races.
     */
    @Test
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `mixed concurrent tokens preserve exact utf8 input and independent startup secrets`() {
        val forward = ExternalIdentityCacheKeyHasher { bytes ->
            bytes.indices.forEach { bytes[it] = it.toByte() }
        }
        val reverse = ExternalIdentityCacheKeyHasher { bytes ->
            bytes.indices.forEach { bytes[it] = (31 - it).toByte() }
        }
        val vectors =
            listOf(
                Triple(
                    forward,
                    "Token-A",
                    "c38e8b8db3f70f482663571d8980ee319631ed36f6931ea4cf6e6d272be3fd87",
                ),
                Triple(
                    forward,
                    "token-A",
                    "c2f26428cb895aba322a3203c080aaea269ed7e3e122cfe96f15d99a441660cb",
                ),
                Triple(
                    forward,
                    "Token-B",
                    "5061d69c2ba03db3506f627b6a9ee262d34a80df09180223a67435e77131e3bf",
                ),
                Triple(
                    forward,
                    "токен-雪",
                    "67d9572f9d41c38963a6c944089b27d46416f9ec9b9068a417445994435c29b7",
                ),
                Triple(
                    reverse,
                    "Token-A",
                    "f4f7ced96e0722cc106ea6c1a800d86876fe9b64c9a1906fe75d389d8421a949",
                ),
                Triple(
                    reverse,
                    "token-A",
                    "930aa95b1a004011ebe3c8aec297456eb48b9516b45895c5e3589334b765f7d8",
                ),
                Triple(
                    reverse,
                    "Token-B",
                    "6a9d4b5d73e57fdecfc93774cc55210889d638beffcd91529bb1f54f01bd652e",
                ),
                Triple(
                    reverse,
                    "токен-雪",
                    "1a9f30f01b544d70a19c28f5169f3a44b40f19eca0b4765f7a738bde51532621",
                ),
            )
        Executors.newFixedThreadPool(8).use { executor ->
            val start = CountDownLatch(1)
            val tasks = vectors.map { (hasher, token, expected) ->
                executor.submit {
                    check(start.await(2, TimeUnit.SECONDS))
                    repeat(100) {
                        assertEquals(expected, hasher.keyFor(String(token.toCharArray())))
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        }
    }

    /** RFC 4231 case 1 remains identical when the short key is padded with HMAC block zeroes. */
    @Test
    fun `full digest matches RFC 4231 vector and equal string contents`() {
        val hasher = ExternalIdentityCacheKeyHasher { bytes ->
            bytes.fill(0)
            bytes.fill(0x0b, 0, 20)
        }
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        assertEquals(expected, hasher.keyFor("Hi There"))
        assertEquals(expected, hasher.keyFor(String("Hi There".toCharArray())))
    }
}
