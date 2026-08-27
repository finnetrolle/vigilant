package io.vigilant.protocol.openai

import io.vigilant.protocol.NormalizedProtocolAttributes
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections

/** Complete immutable byte source that can open independent read-only streams. */
fun interface CompleteByteSource {
    /** Opens a stream over the complete source without transferring source ownership. */
    fun openStream(): InputStream

    /** Factories for standalone immutable sources. */
    companion object {
        /** Creates a source backed by a defensive copy of [bytes]. */
        fun copyOf(bytes: ByteArray): CompleteByteSource {
            val snapshot = bytes.copyOf()
            return CompleteByteSource { ByteArrayInputStream(snapshot) }
        }
    }
}

/** Protocol family selected by the transport layer. */
enum class ProtocolFamily {
    /** OpenAI HTTP API. */
    OPENAI,
}

/** Versioned protocol operation selected before reading a body. */
enum class ProtocolOperation {
    /** OpenAI Chat Completions. */
    CHAT_COMPLETIONS,
}

/** Logical protocol direction represented by a parse result. */
enum class ProtocolDirection {
    /** Client request sent toward a provider. */
    REQUEST,

    /** Provider response sent toward a client. */
    RESPONSE,
}

/** Source transport kind represented by an operation descriptor. */
enum class ProtocolTransport {
    /** One complete JSON document. */
    JSON,
}

/** Explicit routing descriptor chosen before parsing a protocol body. */
data class OpenAiOperationDescriptor(
    /** Protocol family. */
    val family: ProtocolFamily,
    /** Protocol operation. */
    val operation: ProtocolOperation,
    /** Exact HTTP method. */
    val method: String,
    /** Exact normalized request path. */
    val normalizedPath: String,
    /** Request media type, optionally including parameters. */
    val mediaType: String,
    /** Logical parse direction. */
    val direction: ProtocolDirection,
    /** Source transport kind. */
    val transport: ProtocolTransport,
    /** Pinned internal protocol contract version. */
    val contract: String,
) {
    /** Supported descriptor constants. */
    companion object {
        /** Pinned OpenAI Chat Completions JSON request descriptor. */
        val CHAT_COMPLETIONS_REQUEST =
            OpenAiOperationDescriptor(
                family = ProtocolFamily.OPENAI,
                operation = ProtocolOperation.CHAT_COMPLETIONS,
                method = "POST",
                normalizedPath = "/v1/chat/completions",
                mediaType = "application/json",
                direction = ProtocolDirection.REQUEST,
                transport = ProtocolTransport.JSON,
                contract = "openai-chat-completions-request@2026-08-26",
            )
    }
}

/** Guardrail-facing semantics of one independent decoded text fragment. */
enum class FragmentSemanticKind {
    /** System or developer instruction text. */
    INSTRUCTION,

    /** User or assistant message text. */
    MESSAGE_TEXT,

    /** Model-visible user-defined label. */
    LABEL,

    /** Model-visible JSON Schema text. */
    SCHEMA_TEXT,

    /** Tool implementation description. */
    TOOL_DESCRIPTION,

    /** Textual tool invocation argument. */
    TOOL_ARGUMENT,

    /** Textual tool result. */
    TOOL_RESULT,

    /** Predicted or returned output text. */
    OUTPUT_TEXT,

    /** Assistant refusal text. */
    REFUSAL,

    /** Available plaintext reasoning or summary. */
    REASONING,
}

/** Explicit Chat Completions message role. */
enum class MessageRole {
    /** Developer instruction. */
    DEVELOPER,

    /** System instruction. */
    SYSTEM,

    /** End-user message. */
    USER,

    /** Assistant message or tool invocation. */
    ASSISTANT,

    /** Tool result. */
    TOOL,

    /** Deprecated function result. */
    FUNCTION,
}

/** Opaque protocol-specific locator retained only for source association. */
@JvmInline
value class ProtocolLocator(
    /** Adapter-owned opaque locator value. */
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Protocol locator must not be blank" }
    }
}

/** Source association and semantics for one text fragment. */
data class FragmentProvenance(
    /** Original fragment order within the parse result. */
    val ordinal: Int,
    /** Logical direction. */
    val direction: ProtocolDirection,
    /** Guardrail-facing semantic kind. */
    val semanticKind: FragmentSemanticKind,
    /** Explicit protocol role, when present. */
    val role: MessageRole?,
    /** Adapter-owned source locator. */
    val locator: ProtocolLocator,
) {
    init {
        require(ordinal >= 0) { "Fragment ordinal must not be negative" }
    }
}

/** One decoded logical text field to inspect independently. */
data class TextFragment(
    /** Exact decoded non-empty field text. */
    val text: String,
    /** Immutable source semantics. */
    val provenance: FragmentProvenance,
) {
    init {
        require(text.isNotEmpty()) { "Text fragment must not be empty" }
    }

    /** Returns safe structural details without the decoded payload text. */
    override fun toString(): String = "TextFragment(provenance=$provenance)"
}

/** Recognized content that current text detectors cannot inspect. */
enum class InspectionGapKind {
    /** Image content. */
    IMAGE,

    /** Audio content. */
    AUDIO,

    /** File content. */
    FILE,

    /** Opaque assistant audio reference. */
    OPAQUE_AUDIO_REFERENCE,

    /** Provider-opaque encrypted reasoning. */
    OPAQUE_REASONING,
}

/** Explicit source location of one recognized inspection gap. */
data class InspectionGap(
    /** Gap category. */
    val kind: InspectionGapKind,
    /** Adapter-owned source locator. */
    val locator: ProtocolLocator,
)

/** Completeness of text inspection for one successfully parsed request. */
enum class InspectionCoverage {
    /** All recognized content is represented by text fragments. */
    FULLY_INSPECTABLE,

    /** Text fragments and recognized inspection gaps are both present. */
    PARTIALLY_INSPECTABLE,

    /** Recognized content exists but no non-empty text fragment is inspectable. */
    UNINSPECTABLE,
}

/** Immutable normalized Chat Completions request. */
class NormalizedChatCompletionsRequest(
    /** Body-derived policy attributes. */
    val attributes: NormalizedProtocolAttributes,
    fragments: Collection<TextFragment>,
    inspectionGaps: Collection<InspectionGap>,
    /** Explicit inspection coverage. */
    val coverage: InspectionCoverage,
) {
    /** Ordered immutable logical text fragments. */
    val fragments: List<TextFragment> = Collections.unmodifiableList(ArrayList(fragments))

    /** Ordered immutable recognized inspection gaps. */
    val inspectionGaps: List<InspectionGap> = Collections.unmodifiableList(ArrayList(inspectionGaps))

    init {
        require((coverage == InspectionCoverage.FULLY_INSPECTABLE) == this.inspectionGaps.isEmpty()) {
            "Fully inspectable requests must have no inspection gaps"
        }
        require(coverage != InspectionCoverage.UNINSPECTABLE || this.fragments.isEmpty()) {
            "Uninspectable requests cannot contain text fragments"
        }
    }
}

/** Stable fail-closed parser categories. */
enum class ChatCompletionsParseFailureCode {
    /** JSON or required supported shape is malformed. */
    MALFORMED_MESSAGE,

    /** Operation or structural budget is unsupported. */
    UNSUPPORTED_SCHEMA,

    /** Content-bearing structure has no unambiguous supported meaning. */
    AMBIGUOUS_CONTENT,

    /** Model-visible referenced context is not locally available. */
    UNRESOLVED_CONTEXT,
}

/** Explicit safe Chat Completions parser result. */
sealed interface ChatCompletionsParseResult {
    /** Successful normalized request. */
    data class Success(
        /** Immutable normalized request. */
        val request: NormalizedChatCompletionsRequest,
    ) : ChatCompletionsParseResult

    /** Typed fail-closed result without source details or partial normalized state. */
    data class Failure(
        /** Stable machine-readable category. */
        val code: ChatCompletionsParseFailureCode,
    ) : ChatCompletionsParseResult
}
