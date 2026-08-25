package io.vigilant.policy.domain

/**
 * Non-empty half-open UTF-8 byte span in an original payload.
 *
 * @property startUtf8 inclusive UTF-8 byte offset.
 * @property endUtf8 exclusive UTF-8 byte offset.
 */
data class Utf8Span(
    val startUtf8: Long,
    val endUtf8: Long,
) {
    init {
        require(startUtf8 >= 0 && endUtf8 > startUtf8) {
            "UTF-8 offsets must define a non-empty byte span"
        }
    }
}
