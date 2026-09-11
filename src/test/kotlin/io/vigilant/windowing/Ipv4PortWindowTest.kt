package io.vigilant.windowing

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Independent global-offset oracles at the published Fast PII ownership boundary. */
class Ipv4PortWindowTest {
    /** Each UTF-8 padding width reaches a boundary inside the address, before colon and inside port. */
    @TestFactory
    fun `ipv4 port evidence crosses ownership boundaries`(): List<DynamicTest> =
        listOf(" ", "я", "€", "😀").flatMapIndexed { widthIndex, filler ->
            listOf(4, 9, 12).map { split ->
                DynamicTest.dynamicTest("width=${widthIndex + 1} boundary-at=$split") {
                    // Published W=1048576, E=4096, C=4095 gives the first ASCII core end 1040386.
                    val start = 1_040_386 - split
                    val width = filler.toByteArray(Charsets.UTF_8).size
                    val prefix = filler.repeat((start - 1) / width) + " ".repeat((start - 1) % width + 1)
                    val fragment = InspectableTextFragment(
                        prefix + "192.0.2.1:443 now" + " ".repeat(8_200), FragmentReference("port-boundary"),
                    )
                    assertEquals(start, prefix.toByteArray(Charsets.UTF_8).size)
                    assertEquals(1_048_576, FastPiiWindowCapability.VERSIONED.maxWindowUtf8Bytes)
                    assertEquals(4_096, FastPiiWindowCapability.VERSIONED.maximumEvidenceSpanUtf8Bytes)
                    assertTrue((prefix + "192.0.2.1:443 now").toByteArray().size + 8_200 > 1_048_576)

                    Executors.newSingleThreadExecutor().use { cpu ->
                        val success = assertIs<WindowedPiiInspectionResult.Success>(
                            WindowedFastPiiExecutor(cpu).inspect(fragment, setOf(PiiType.IP_ADDRESS))
                                .get(10, TimeUnit.SECONDS),
                        )
                        assertEquals(fragment.provenance, success.provenance)
                        assertEquals(listOf(PiiFinding(
                            PiiType.IP_ADDRESS, start.toLong(), start + 9L, null,
                            EvidenceStrength.VALIDATED, "fast.ip_address", "1.2.0",
                        )), success.findings)
                    }
                }
            }
        }
}
