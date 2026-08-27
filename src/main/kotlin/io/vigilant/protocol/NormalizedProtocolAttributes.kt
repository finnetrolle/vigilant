package io.vigilant.protocol

/** Provider-neutral body-derived attributes used to assemble a policy context. */
data class NormalizedProtocolAttributes(
    /** Exact decoded request model. */
    val model: String,
)
