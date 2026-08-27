package io.vigilant.context

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyId
import io.vigilant.policy.domain.PolicyMatch
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.PolicyReactions
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicySubject
import io.vigilant.policy.domain.PolicyVersion
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.SubjectId
import io.vigilant.policy.domain.SubjectType
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.protocol.NormalizedProtocolAttributes
import java.time.Duration
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Public behavior tests for anonymous request policy-context assembly. */
@Suppress("MaxLineLength")
class AnonymousRequestContextAssemblerTest {
    /** Exact normalized values form an anonymous REQUEST context matched only by global ANY policy. */
    @Test
    fun `anonymous request context applies global any policy without identity`() {
        val originalLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val normalizedUrl =
                assertIs<PolicyUrlNormalizationResult.Success>(
                    PolicyUrlNormalizer.normalize("https://LLM.Example.:443/v1/../v1/chat/completions?secret=value"),
                ).url
            val result =
                AnonymousRequestContextAssembler.assemble(
                    normalizedUrl,
                    NormalizedProtocolAttributes("PII-MODEL"),
                )
            val context = assertIs<AnonymousRequestContextAssemblyResult.Success>(result).context

            assertEquals("https://llm.example/v1/chat/completions", context.url)
            assertEquals("PII-MODEL", context.model)
            assertEquals(PolicyPhase.REQUEST, context.phase)
            assertNull(context.user)
            assertEquals(emptySet(), context.groups)

            val policies =
                listOf(
                    policy("global", SubjectType.ANY, "*"),
                    policy("user", SubjectType.USER, "anonymous"),
                    policy("group", SubjectType.GROUP, "anonymous"),
                )
            assertEquals(
                listOf("global"),
                PolicySelector().select(policies, context).applied.map { policy -> policy.reference.id.value },
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    /** Missing typed inputs produce one safe assembly failure and no partial context. */
    @Test
    fun `missing normalized inputs return a typed safe failure`() {
        val url = NormalizedPolicyUrl("https://llm.example/v1/chat/completions")
        val attributes = NormalizedProtocolAttributes("gpt-5")

        listOf(
            AnonymousRequestContextAssembler.assemble(null, attributes),
            AnonymousRequestContextAssembler.assemble(url, null),
        ).forEach { result ->
            assertEquals(
                AnonymousRequestContextAssemblyResult.Failure(AnonymousRequestContextAssemblyErrorCode.INVALID_CONTEXT_INPUT),
                result,
            )
        }
    }

    /** Blank protocol attributes produce one safe assembly failure and no partial context. */
    @Test
    fun `blank normalized model returns a typed safe failure`() {
        val result =
            AnonymousRequestContextAssembler.assemble(
                NormalizedPolicyUrl("https://llm.example/v1/chat/completions"),
                NormalizedProtocolAttributes(" \t"),
            )

        assertEquals(
            AnonymousRequestContextAssemblyResult.Failure(
                AnonymousRequestContextAssemblyErrorCode.INVALID_CONTEXT_INPUT,
            ),
            result,
        )
        assertEquals("Failure(code=INVALID_CONTEXT_INPUT)", result.toString())
    }

    /** Contradictory typed URL values fail safely instead of being canonicalized a second time. */
    @Test
    fun `contradictory normalized url is rejected without partial context`() {
        val contradictoryUrls =
            listOf(
                "",
                "   ",
                "HTTPS://llm.example/path",
                "https://LLM.example/path",
                "https://llm.example./path",
                "https://llm.example:443/path",
                "https://llm.example/a/../path",
                "https://llm.example/%7Euser",
                "https://llm.example/a%2fpath",
                "https://[2001:0db8:0:0:0:0:0:1]/path",
                "https://user:secret@llm.example/path",
                "https://llm.example/path?secret=value",
                "https://llm.example",
                "not-a-uri",
            )

        contradictoryUrls.forEach { value ->
            val result =
                AnonymousRequestContextAssembler.assemble(
                    NormalizedPolicyUrl(value),
                    NormalizedProtocolAttributes("gpt-5"),
                )

            assertEquals(
                AnonymousRequestContextAssemblyResult.Failure(
                    AnonymousRequestContextAssemblyErrorCode.INVALID_CONTEXT_INPUT,
                ),
                result,
            )
            assertEquals("Failure(code=INVALID_CONTEXT_INPUT)", result.toString())
        }
    }

    /** Anonymous group state is an immutable empty snapshot. */
    @Test
    fun `assembled anonymous groups cannot be mutated`() {
        val result =
            AnonymousRequestContextAssembler.assemble(
                NormalizedPolicyUrl("https://llm.example/path"),
                NormalizedProtocolAttributes("gpt-5"),
            )
        val context = assertIs<AnonymousRequestContextAssemblyResult.Success>(result).context

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (context.groups as MutableSet<String>).add("group")
        }
    }

    /** Creates one enabled request policy for selector consumer evidence. */
    private fun policy(
        id: String,
        subjectType: SubjectType,
        subjectId: String,
    ): Policy =
        Policy(
            reference = PolicyReference(PolicyId(id), PolicyVersion("1")),
            enabled = true,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.REQUEST,
                    subject = PolicySubject(subjectType, SubjectId(subjectId)),
                ),
            detectors = listOf(DetectorId("fast-pii")),
            deadline = Duration.ofMillis(50),
            reactions =
                PolicyReactions(
                    detected = Reaction(Disposition.ALLOW, emptyList()),
                    clean = Reaction(Disposition.ALLOW, emptyList()),
                    error = Reaction(Disposition.ALLOW, emptyList()),
                ),
            overrides = emptyList(),
        )
}
