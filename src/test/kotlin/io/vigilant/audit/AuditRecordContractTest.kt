package io.vigilant.audit

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies the immutable bounded safe-record contract exposed to audit producers. */
class AuditRecordContractTest {
    /** Startup capacity measures the largest valid scalar and aggregate encodings exactly. */
    @Test
    fun `policy capacity rejects one byte below the actual worst case frame`() {
        val policies = listOf(AuditComponentReference("shadow", "1"))
        val counts = maximumWidthAggregateCounts()
        val worstCaseRecord =
            AuditRecord(
                eventId = UUID(0, 0),
                createdAt = Instant.MAX,
                traceId = "0".repeat(32),
                decision = AuditDecision.ERROR,
                coverage = AuditCoverage.PARTIALLY_INSPECTABLE,
                policies = policies,
                detectors = listOf(AuditComponentReference("fast-pii", "fast-pii@1")),
                inspectedFragments = Int.MAX_VALUE,
                totalFindings = Int.MAX_VALUE,
                findingsByType = counts,
                findingsByEvidenceStrength = counts,
                evaluationDuration = Duration.ofNanos(Long.MAX_VALUE),
                errorCode = "E".repeat(128),
            )
        val worstCaseFrameBytes =
            AuditRecordCodec.encode(
                sequence = Long.MAX_VALUE,
                record = worstCaseRecord,
                maxEventBytes = Int.MAX_VALUE,
            ).size

        assertFailsWith<IllegalArgumentException> {
            validateAuditSchemaCapacity(policies, worstCaseFrameBytes - 1)
        }
        validateAuditSchemaCapacity(policies, worstCaseFrameBytes)
    }

    /** Startup capacity accepts a small snapshot and rejects a worst-case oversized one. */
    @Test
    fun `policy references fit the configured safe record bound`() {
        validateAuditSchemaCapacity(
            policies = listOf(AuditComponentReference("shadow", "1")),
            maxEventBytes = 65_536,
        )
        val oversized =
            (0 until 400).map { index ->
                AuditComponentReference("policy-${index.toString().padStart(3, '0')}-${"x".repeat(110)}", "version-1")
            }

        assertFailsWith<IllegalArgumentException> {
            validateAuditSchemaCapacity(oversized, 65_536)
        }
    }

    /** Copies and sorts caller-owned references and aggregate maps without retaining mutable inputs. */
    @Test
    fun `record canonicalizes caller owned collections`() {
        val policies =
            mutableListOf(
                AuditComponentReference("policy-b", "2"),
                AuditComponentReference("policy-a", "1"),
            )
        val countsByType = mutableMapOf("PHONE_NUMBER" to 1, "EMAIL_ADDRESS" to 1)
        val countsByEvidence = mutableMapOf("VALIDATED" to 1, "FORMAT_ONLY" to 1)

        val record =
            AuditRecord(
                eventId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
                createdAt = Instant.parse("2026-08-30T00:00:00Z"),
                traceId = "0123456789abcdef0123456789abcdef",
                decision = AuditDecision.DETECTED,
                coverage = AuditCoverage.FULLY_INSPECTABLE,
                policies = policies,
                detectors = listOf(AuditComponentReference("fast-pii", "1.0.0")),
                inspectedFragments = 2,
                totalFindings = 2,
                findingsByType = countsByType,
                findingsByEvidenceStrength = countsByEvidence,
                evaluationDuration = Duration.ofMillis(7),
            )

        policies.clear()
        countsByType.clear()
        countsByEvidence.clear()

        assertEquals(listOf("policy-a", "policy-b"), record.policies.map(AuditComponentReference::id))
        assertEquals(listOf("EMAIL_ADDRESS", "PHONE_NUMBER"), record.findingsByType.keys.toList())
        assertEquals(listOf("FORMAT_ONLY", "VALIDATED"), record.findingsByEvidenceStrength.keys.toList())
    }

    /** Accepts every current decision with empty or populated bounded aggregate classes. */
    @Test
    fun `safe schema accepts complete decision matrix`() {
        val records =
            listOf(
                record(decision = AuditDecision.CLEAN),
                record(
                    decision = AuditDecision.DETECTED,
                    totalFindings = 1,
                    findingsByType = mapOf("EMAIL_ADDRESS" to 1),
                    findingsByEvidence = mapOf("FORMAT_ONLY" to 1),
                ),
                record(
                    decision = AuditDecision.INSPECTION_GAP,
                    coverage = AuditCoverage.PARTIALLY_INSPECTABLE,
                ),
                record(
                    decision = AuditDecision.ERROR,
                    coverage = AuditCoverage.UNINSPECTABLE,
                    errorCode = "MALFORMED_MESSAGE",
                    policies = emptyList(),
                    detectors = emptyList(),
                ),
            )

        assertEquals(AuditDecision.entries, records.map(AuditRecord::decision))
    }

    /** Rejects unsafe, unbounded, contradictory, or non-canonical scalar inputs. */
    @Test
    fun `safe schema rejects invalid values`() {
        val invalid =
            listOf<() -> Unit>(
                { record(traceId = "secret-trace") },
                { record(inspectedFragments = -1) },
                { record(totalFindings = -1) },
                { record(evaluationDuration = Duration.ofNanos(-1)) },
                { record(evaluationDuration = Duration.ofSeconds(Long.MAX_VALUE)) },
                { record(totalFindings = 1) },
                { record(findingsByType = mapOf("EMAIL_ADDRESS" to 0)) },
                { record(decision = AuditDecision.ERROR) },
                { record(decision = AuditDecision.CLEAN, errorCode = "SHOULD_NOT_EXIST") },
                { record(errorCode = "raw exception message with spaces") },
                { record(policies = listOf(AuditComponentReference("bad id", "1"))) },
                { record(detectors = listOf(AuditComponentReference("fast-pii", ""))) },
            )

        invalid.forEach { create ->
            assertFailsWith<IllegalArgumentException> { create() }
        }
    }

    /** Builds one known-safe record while allowing one criterion to vary. */
    @Suppress("LongParameterList")
    private fun record(
        traceId: String = "0123456789abcdef0123456789abcdef",
        decision: AuditDecision = AuditDecision.CLEAN,
        coverage: AuditCoverage = AuditCoverage.FULLY_INSPECTABLE,
        policies: List<AuditComponentReference> = listOf(AuditComponentReference("policy-a", "1")),
        detectors: List<AuditComponentReference> = listOf(AuditComponentReference("fast-pii", "1.0.0")),
        inspectedFragments: Int = 1,
        totalFindings: Int = 0,
        findingsByType: Map<String, Int> = emptyMap(),
        findingsByEvidence: Map<String, Int> = emptyMap(),
        evaluationDuration: Duration = Duration.ofMillis(1),
        errorCode: String? = null,
    ): AuditRecord =
        AuditRecord(
            eventId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
            createdAt = Instant.parse("2026-08-30T00:00:00Z"),
            traceId = traceId,
            decision = decision,
            coverage = coverage,
            policies = policies,
            detectors = detectors,
            inspectedFragments = inspectedFragments,
            totalFindings = totalFindings,
            findingsByType = findingsByType,
            findingsByEvidenceStrength = findingsByEvidence,
            evaluationDuration = evaluationDuration,
            errorCode = errorCode,
        )

    /** Builds an independent 528-digit witness across 64 valid positive counts. */
    private fun maximumWidthAggregateCounts(): Map<String, Int> {
        return (0 until 64).associate { index ->
            val prefix = "CLASS_${index.toString().padStart(2, '0')}_"
            val count =
                when {
                    index < 16 -> 100_000_000
                    index < 63 -> 10_000_000
                    else -> 77_483_647
                }
            (prefix + "X".repeat(64 - prefix.length)) to count
        }
    }
}
