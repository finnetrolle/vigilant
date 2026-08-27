package io.vigilant.context

import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.protocol.NormalizedProtocolAttributes
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Public behavior tests for assembling policy contexts from normalized inputs. */
class PolicyContextAssemblerTest {
    /** Valid normalized inputs are transferred exactly into one request policy context. */
    @Test
    fun `normalized inputs form an exact request context`() {
        val normalizedUrl = NormalizedPolicyUrl("https://llm.example/v1/chat/completions")
        val identity = NormalizedIdentity(user = "alice", groups = listOf("security", "operators"))
        val attributes = NormalizedProtocolAttributes(model = " Provider-Model-Exact ")

        val result =
            PolicyContextAssembler.assemble(
                normalizedUrl = normalizedUrl,
                identity = identity,
                phase = PolicyPhase.REQUEST,
                attributes = attributes,
            )

        val context = assertIs<PolicyContextAssemblyResult.Success>(result).context
        assertEquals("https://llm.example/v1/chat/completions", context.url)
        assertEquals(" Provider-Model-Exact ", context.model)
        assertEquals(PolicyPhase.REQUEST, context.phase)
        assertEquals("alice", context.user)
        assertEquals(setOf("operators", "security"), context.groups)
    }

    /** Every missing normalized input returns the same explicit safe failure. */
    @Test
    fun `missing normalized inputs return a typed failure`() {
        val url = NormalizedPolicyUrl("https://llm.example/v1/chat/completions")
        val identity = NormalizedIdentity(user = null, groups = emptyList())
        val attributes = NormalizedProtocolAttributes(model = "gpt-5")
        val inputs =
            listOf(
                AssemblyInput(null, identity, PolicyPhase.REQUEST, attributes),
                AssemblyInput(url, null, PolicyPhase.REQUEST, attributes),
                AssemblyInput(url, identity, null, attributes),
                AssemblyInput(url, identity, PolicyPhase.REQUEST, null),
            )

        inputs.forEach { input ->
            assertEquals(
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.MISSING_CONTEXT_INPUT),
                PolicyContextAssembler.assemble(input.url, input.identity, input.phase, input.attributes),
            )
        }
    }

    /** Response phase is contradictory for request-context assembly. */
    @Test
    fun `response phase returns a typed contradictory phase failure`() {
        val result =
            PolicyContextAssembler.assemble(
                normalizedUrl = NormalizedPolicyUrl("https://llm.example/v1/chat/completions"),
                identity = NormalizedIdentity(user = null, groups = emptyList()),
                phase = PolicyPhase.RESPONSE,
                attributes = NormalizedProtocolAttributes(model = "gpt-5"),
            )

        assertEquals(
            PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.CONTRADICTORY_PHASE),
            result,
        )
    }

    /** Invalid normalized candidates fail explicitly without a partial context. */
    @Test
    fun `invalid normalized inputs return source specific typed failures`() {
        val url = NormalizedPolicyUrl("https://llm.example/v1/chat/completions")
        val identity = NormalizedIdentity(user = "alice", groups = listOf("operators"))
        val attributes = NormalizedProtocolAttributes(model = "gpt-5")
        val inputs =
            listOf(
                InvalidInput(
                    url = NormalizedPolicyUrl("HTTPS://llm.example/v1/chat/completions"),
                    identity = identity,
                    attributes = attributes,
                    expectedCode = PolicyContextAssemblyErrorCode.INVALID_NORMALIZED_URL,
                ),
                InvalidInput(
                    url = url,
                    identity = NormalizedIdentity(user = "Alice", groups = emptyList()),
                    attributes = attributes,
                    expectedCode = PolicyContextAssemblyErrorCode.INVALID_NORMALIZED_IDENTITY,
                ),
                InvalidInput(
                    url = url,
                    identity = NormalizedIdentity(user = null, groups = listOf("operators", "operators")),
                    attributes = attributes,
                    expectedCode = PolicyContextAssemblyErrorCode.INVALID_NORMALIZED_IDENTITY,
                ),
                InvalidInput(
                    url = url,
                    identity = NormalizedIdentity(user = null, groups = List(129) { index -> "group$index" }),
                    attributes = attributes,
                    expectedCode = PolicyContextAssemblyErrorCode.INVALID_NORMALIZED_IDENTITY,
                ),
                InvalidInput(
                    url = url,
                    identity = identity,
                    attributes = NormalizedProtocolAttributes(model = " \t"),
                    expectedCode = PolicyContextAssemblyErrorCode.INVALID_PROTOCOL_ATTRIBUTES,
                ),
            )

        inputs.forEach { input ->
            assertEquals(
                PolicyContextAssemblyResult.Failure(input.expectedCode),
                PolicyContextAssembler.assemble(
                    input.url,
                    input.identity,
                    PolicyPhase.REQUEST,
                    input.attributes,
                ),
            )
        }
    }

    /** Repeated assembly is structural and snapshots mutable normalized identity input. */
    @Test
    fun `same normalized inputs produce immutable structurally equal contexts`() {
        val originalLocale = Locale.getDefault()
        val mutableGroups = mutableListOf("operators", "security")
        val identity = NormalizedIdentity(user = null, groups = mutableGroups)
        mutableGroups += "late-mutation"

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val results =
                listOf(
                    identity,
                    NormalizedIdentity(user = null, groups = listOf("security", "operators")),
                ).map { normalizedIdentity ->
                    PolicyContextAssembler.assemble(
                        normalizedUrl = NormalizedPolicyUrl("https://llm.example/v1/chat/completions"),
                        identity = normalizedIdentity,
                        phase = PolicyPhase.REQUEST,
                        attributes = NormalizedProtocolAttributes(model = "PII-MODEL"),
                    )
                }
            val contexts = results.map { result -> assertIs<PolicyContextAssemblyResult.Success>(result).context }

            assertEquals(
                ContextSnapshot(
                    url = "https://llm.example/v1/chat/completions",
                    model = "PII-MODEL",
                    phase = PolicyPhase.REQUEST,
                    user = null,
                    groups = setOf("operators", "security"),
                ),
                contexts.first().toSnapshot(),
            )
            assertEquals(contexts.first().toSnapshot(), contexts.last().toSnapshot())
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (contexts.first().groups as MutableSet<String>).add("forbidden")
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    /** Nullable input row used by the missing-input behavior table. */
    private data class AssemblyInput(
        val url: NormalizedPolicyUrl?,
        val identity: NormalizedIdentity?,
        val phase: PolicyPhase?,
        val attributes: NormalizedProtocolAttributes?,
    )

    /** Invalid input row paired with its safe failure category. */
    private data class InvalidInput(
        val url: NormalizedPolicyUrl,
        val identity: NormalizedIdentity,
        val attributes: NormalizedProtocolAttributes,
        val expectedCode: PolicyContextAssemblyErrorCode,
    )

    /** Structural projection used as an independent deterministic expected value. */
    private data class ContextSnapshot(
        val url: String,
        val model: String,
        val phase: PolicyPhase,
        val user: String?,
        val groups: Set<String>,
    )

    /** Projects public context fields without relying on object identity. */
    private fun io.vigilant.policy.domain.PolicyContext.toSnapshot(): ContextSnapshot =
        ContextSnapshot(url, model, phase, user, groups)
}
