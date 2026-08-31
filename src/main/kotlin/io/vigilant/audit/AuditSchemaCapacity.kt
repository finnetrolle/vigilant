package io.vigilant.audit

import java.time.Instant
import java.util.UUID

/**
 * Validates that one configured policy population fits the worst-case bounded safe schema.
 *
 * This startup check prevents a valid runtime decision from discovering an oversized event
 * only after request admission. Failure never includes policy identities or record contents.
 */
internal fun validateAuditSchemaCapacity(
    policies: List<AuditComponentReference>,
    maxEventBytes: Int,
) {
    try {
        val counts = maximumWidthAggregateCounts()
        val record =
            AuditRecord(
                eventId = UUID(0, 0),
                createdAt = Instant.MAX,
                traceId = "0".repeat(AuditSchemaLimits.TRACE_ID_LENGTH),
                decision = AuditDecision.ERROR,
                coverage = AuditCoverage.PARTIALLY_INSPECTABLE,
                policies = policies,
                detectors = listOf(AuditComponentReference("fast-pii", "fast-pii@1")),
                inspectedFragments = AuditSchemaLimits.MAX_COUNT,
                totalFindings = AuditSchemaLimits.MAX_COUNT,
                findingsByType = counts,
                findingsByEvidenceStrength = counts,
                evaluationDuration = AuditSchemaLimits.MAX_EVALUATION_DURATION,
                errorCode = "E".repeat(AuditSchemaLimits.MAX_ERROR_CODE_LENGTH),
            )
        AuditRecordCodec.encode(sequence = Long.MAX_VALUE, record = record, maxEventBytes = maxEventBytes)
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("Policy snapshot exceeds the audit record bound")
    }
}

/** Returns maximum-length classes whose positive counts maximize valid encoded width. */
private fun maximumWidthAggregateCounts(): Map<String, Int> {
    val counts = IntArray(AuditSchemaLimits.MAX_AGGREGATE_CLASSES) { 1 }
    var remaining = AuditSchemaLimits.MAX_COUNT.toLong() - counts.size
    var digitThreshold = DECIMAL_RADIX
    promotion@ while (digitThreshold <= Int.MAX_VALUE.toLong()) {
        for (index in counts.indices) {
            val promotionCost = digitThreshold - counts[index]
            if (promotionCost > remaining) break@promotion
            counts[index] = digitThreshold.toInt()
            remaining -= promotionCost
        }
        digitThreshold *= DECIMAL_RADIX
    }
    counts[counts.lastIndex] = (counts.last() + remaining).toInt()
    return (0 until AuditSchemaLimits.MAX_AGGREGATE_CLASSES).associate { index ->
        val prefix = "CLASS_${index.toString().padStart(2, '0')}_"
        (prefix + "X".repeat(AuditSchemaLimits.MAX_AGGREGATE_CLASS_LENGTH - prefix.length)) to counts[index]
    }
}

/** Decimal radix used to promote every count through the cheapest width boundary first. */
private const val DECIMAL_RADIX = 10L
