package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Public detector examples for the IPv4 port boundary contract. */
class Ipv4PortRecognizerTest {
    /** Every port edge, prose separator and UTF-8 prefix width has an independent exact finding. */
    @TestFactory
    fun `valid ports preserve address spans before prose`(): List<DynamicTest> =
        listOf("x ", "я ", "€ ", "😀 ").flatMapIndexed { widthIndex, prefix ->
            listOf("1", "443", "65535").flatMap { port ->
                listOf("", " now", "\tnow", "\nnow", "\r\nnow").mapIndexed { suffixIndex, suffix ->
                    DynamicTest.dynamicTest("width=${widthIndex + 1} port=$port suffix=$suffixIndex") {
                        val address = "192.0.2.1"
                        val start = prefix.toByteArray(Charsets.UTF_8).size.toLong()
                        val expected = PiiFinding(
                            PiiType.IP_ADDRESS, start, start + address.toByteArray(Charsets.UTF_8).size,
                            null, EvidenceStrength.VALIDATED, "fast.ip_address", "1.2.0",
                        )
                        assertEquals(listOf(expected), FastPiiDetector().detect(
                            "$prefix$address:$port$suffix", false, setOf(PiiType.IP_ADDRESS),
                        ))
                    }
                }
            }
        }

    /** Invalid ports, addresses and left boundaries never expose a valid prefix, even before prose. */
    @TestFactory
    fun `invalid port candidates do not expose valid prefixes`(): List<DynamicTest> =
        listOf(
            "192.0.2.1:0", "192.0.2.1:65536", "192.0.2.1:99999999999999999",
            "192.0.2.1:+443", "192.0.2.1:-443", "192.0.2.1:abc",
            "192.0.2.1:443a", "192.0.2.1:443g", "192.0.2.1:443G",
            "192.0.2.1:443:1", "192.0.2.1:443:", "192.0.2.1::443",
            "192.0.2.1:443.1", "192.0.2.1:443_tag", "192.0.2.1:443%zone",
            "256.0.2.1:443", "192.0.2.999:443", "192.00.2.1:443", "192.0.2:443",
            "192.0.2.1.5:443", "a192.0.2.1:443", "g192.0.2.1:443",
            "9192.0.2.1:443", ".192.0.2.1:443", ":192.0.2.1:443",
        ).flatMapIndexed { caseIndex, candidate ->
            listOf("", " now", "\tnow", "\nnow", "\r\nnow").mapIndexed { suffixIndex, suffix ->
                DynamicTest.dynamicTest("negative=$caseIndex suffix=$suffixIndex") {
                    assertEquals(emptyList(), FastPiiDetector().detect(
                        "😀 $candidate$suffix", false, setOf(PiiType.IP_ADDRESS),
                    ))
                }
            }
        }

    /** Empty port syntax remains ordinary terminal punctuation as explicitly agreed by the owner. */
    @TestFactory
    fun `terminal colon remains punctuation`(): List<DynamicTest> =
        listOf("", " now", "\tnow", "\nnow", "\r\nnow").mapIndexed { index, suffix ->
            DynamicTest.dynamicTest("terminal colon suffix=$index") {
                assertEquals(listOf(PiiFinding(
                    PiiType.IP_ADDRESS, 5, 14, null, EvidenceStrength.VALIDATED, "fast.ip_address", "1.2.0",
                )), FastPiiDetector().detect("😀 192.0.2.1:$suffix", false, setOf(PiiType.IP_ADDRESS)))
            }
        }
}
