package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behavioral tests for the built-in IP_ADDRESS recognizer. */
class IpAddressRecognizerTest {
    /** Verifies strict IPv4 and bracketed compressed IPv6 spans and stable validated metadata. */
    @Test
    fun `supported ipv4 and ipv6 forms produce validated findings`() {
        val findings =
            FastPiiDetector().detect(
                payload = "IPv4 192.168.1.1 IPv6 [2001:db8::1]",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.IP_ADDRESS),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.IP_ADDRESS,
                    startUtf8 = 5,
                    endUtf8 = 16,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.VALIDATED,
                    recognizerId = "fast.ip_address",
                    recognizerVersion = "1.0.0",
                ),
                PiiFinding(
                    type = PiiType.IP_ADDRESS,
                    startUtf8 = 23,
                    endUtf8 = 34,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.VALIDATED,
                    recognizerId = "fast.ip_address",
                    recognizerVersion = "1.0.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies octet extremes plus private, loopback, link-local, and Unicode-adjacent IPv4 values. */
    @Test
    fun `ipv4 accepts every address class at strict octet boundaries`() {
        val detector = FastPiiDetector()
        val validAddresses =
            listOf(
                "0.0.0.0",
                "255.255.255.255",
                "10.0.0.1",
                "127.0.0.1",
                "169.254.1.1",
            )

        validAddresses.forEachIndexed { caseIndex, address ->
            assertEquals(
                1,
                detector.detect(address, enabledTypes = setOf(PiiType.IP_ADDRESS)).size,
                "Missing valid IPv4 boundary case $caseIndex",
            )
        }
        assertEquals(
            listOf(5L to 14L),
            detector
                .detect("😀 127.0.0.1", enabledTypes = setOf(PiiType.IP_ADDRESS))
                .map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
    }

    /** Verifies strict octet count, value, leading-zero, and mixed-text candidate boundaries. */
    @Test
    fun `malformed and embedded ipv4 candidates are rejected`() {
        val hardNegatives =
            listOf(
                "256.0.0.1",
                "1.2.3.999",
                "01.2.3.4",
                "1.02.3.4",
                "1.2.3",
                "1.2.3.",
                "1.2.3.4.5",
                "1..2.3.4",
                "1.2.3.4.",
                "a1.2.3.4",
                "g1.2.3.4",
                "1.2.3.4a",
                "1.2.3.4g",
                "1.2.3.4:5",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.IP_ADDRESS)),
                "Unexpected finding for malformed IPv4 case $caseIndex",
            )
        }
    }

    /** Verifies full, compressed, loopback, link-local, bracketed, and embedded-IPv4 IPv6 forms. */
    @Test
    fun `ipv6 accepts supported compression and embedded ipv4 tails once`() {
        val validAddresses =
            listOf(
                "2001:0db8:0000:0000:0000:ff00:0042:8329",
                "2001:db8::ff00:42:8329",
                "::1",
                "::",
                "fe80::1",
                "::ffff:192.0.2.128",
                "2001:db8:0:0:0:ffff:192.0.2.128",
            )

        validAddresses.forEachIndexed { caseIndex, address ->
            assertEquals(
                1,
                FastPiiDetector().detect(address, stopOnFirst = false, enabledTypes = setOf(PiiType.IP_ADDRESS)).size,
                "Missing or duplicate supported IPv6 case $caseIndex",
            )
        }
        assertEquals(
            listOf(1L to 4L),
            FastPiiDetector()
                .detect("[::1]", enabledTypes = setOf(PiiType.IP_ADDRESS))
                .map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
    }

    /** Verifies malformed compression, group counts, IPv4 tails, and zone identifiers are rejected. */
    @Test
    fun `malformed and zoned ipv6 candidates are rejected`() {
        val hardNegatives =
            listOf(
                "2001:db8:::1",
                "2001:db8::1::",
                "1:2:3:4:5:6:7",
                "1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7::8",
                "12345::1",
                ":2001:db8::1",
                "2001:db8::1:",
                "::ffff:192.168.001.1",
                "::ffff:192.168.1.999",
                "::192.0.2.1:1",
                "fe80::1%eth0",
                "[fe80::1%eth0]",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.IP_ADDRESS)),
                "Unexpected finding for malformed IPv6 case $caseIndex",
            )
        }
    }

    /** Verifies mixed-text continuation and suppression of a separate embedded IPv4-tail finding. */
    @Test
    fun `mixed text continues after invalid addresses in source order`() {
        val findings =
            FastPiiDetector().detect(
                payload = "bad 999.1.1.1; good 192.0.2.1; bad 2001:::1; good ::ffff:192.0.2.128",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.IP_ADDRESS),
            )

        assertEquals(
            listOf(20L to 29L, 50L to 68L),
            findings.map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
    }

    /** Exercises both bounded parsers with maximal malformed candidates. */
    @Test
    fun `adversarial maximal ip candidates are rejected`() {
        val payloads =
            listOf(
                ":".repeat(1_048_576),
                "9".repeat(1_048_570) + ".1.1.1",
            )

        payloads.forEach { payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.IP_ADDRESS)),
            )
        }
    }

}
