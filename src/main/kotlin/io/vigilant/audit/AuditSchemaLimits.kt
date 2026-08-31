package io.vigilant.audit

import java.time.Duration

/** Canonical scalar and collection limits shared by audit validation and capacity planning. */
internal object AuditSchemaLimits {
    /** Maximum number of policy or detector references in one record collection. */
    const val MAX_COMPONENT_REFERENCES: Int = 1_024

    /** Maximum character length of one component identifier or version. */
    const val MAX_COMPONENT_VALUE_LENGTH: Int = 128

    /** Maximum number of classes in either aggregate finding map. */
    const val MAX_AGGREGATE_CLASSES: Int = 64

    /** Maximum character length of one aggregate finding class. */
    const val MAX_AGGREGATE_CLASS_LENGTH: Int = 64

    /** Maximum character length of one stable error code. */
    const val MAX_ERROR_CODE_LENGTH: Int = 128

    /** Exact lowercase hexadecimal trace identifier length. */
    const val TRACE_ID_LENGTH: Int = 32

    /** Largest non-negative count representable by the record and codec. */
    const val MAX_COUNT: Int = Int.MAX_VALUE

    /** Largest non-negative duration representable as encoded nanoseconds. */
    val MAX_EVALUATION_DURATION: Duration = Duration.ofNanos(Long.MAX_VALUE)

    /** Exact safe trace identifier shape. */
    val TRACE_ID: Regex = Regex("[0-9a-f]{$TRACE_ID_LENGTH}")

    /** Safe component identifier and version shape. */
    val SAFE_COMPONENT_VALUE: Regex =
        Regex("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,${MAX_COMPONENT_VALUE_LENGTH - 1}}")

    /** Safe aggregate finding class shape. */
    val SAFE_AGGREGATE_CLASS: Regex =
        Regex("[A-Z][A-Z0-9_]{0,${MAX_AGGREGATE_CLASS_LENGTH - 1}}")

    /** Safe stable error code shape. */
    val SAFE_ERROR_CODE: Regex = Regex("[A-Z][A-Z0-9_]{0,${MAX_ERROR_CODE_LENGTH - 1}}")
}
