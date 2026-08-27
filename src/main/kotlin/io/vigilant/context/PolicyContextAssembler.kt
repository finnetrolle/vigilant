package io.vigilant.context

import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.protocol.NormalizedProtocolAttributes
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

/** Immutable normalized subject identity used for policy-context assembly. */
class NormalizedIdentity(
    /** Normalized user identity, when present. */
    val user: String?,
    /** Candidate normalized group identities. */
    groups: Collection<String>,
) {
    /** Defensive snapshot used to validate collection-level invariants. */
    private val groupSnapshot = ArrayList(groups)

    /** Immutable normalized group identities. */
    val groups: Set<String> = Collections.unmodifiableSet(LinkedHashSet(groupSnapshot))

    /** Whether this candidate exactly satisfies the normalized identity contract. */
    internal val isCanonical: Boolean =
        (user == null || user.isNormalizedIdentityToken()) &&
            groupSnapshot.size <= MAX_IDENTITY_GROUPS &&
            groupSnapshot.size == this.groups.size &&
            groupSnapshot.all(String::isNormalizedIdentityToken)

    /** Normalized identity contract limits. */
    private companion object {
        /** Maximum number of distinct normalized group identities. */
        const val MAX_IDENTITY_GROUPS = 128
    }
}

/** Stable policy-context assembly failure categories. */
enum class PolicyContextAssemblyErrorCode {
    /** At least one required normalized input is absent. */
    MISSING_CONTEXT_INPUT,

    /** The explicit phase cannot be assembled by the request-only contract. */
    CONTRADICTORY_PHASE,

    /** The typed URL candidate is not in canonical policy form. */
    INVALID_NORMALIZED_URL,

    /** The typed identity candidate violates normalized identity invariants. */
    INVALID_NORMALIZED_IDENTITY,

    /** Body-derived attributes violate their normalized contract. */
    INVALID_PROTOCOL_ATTRIBUTES,
}

/** Explicit result of assembling one policy context. */
sealed interface PolicyContextAssemblyResult {
    /** Successful immutable policy context. */
    data class Success(
        /** Engine-owned policy context. */
        val context: PolicyContext,
    ) : PolicyContextAssemblyResult

    /** Safe typed failure without a partial context. */
    data class Failure(
        /** Stable machine-readable failure category. */
        val code: PolicyContextAssemblyErrorCode,
    ) : PolicyContextAssemblyResult
}

/** Pure assembler for policy contexts built only from normalized inputs. */
object PolicyContextAssembler {
    /**
     * Assembles one policy context without parsing or normalizing source data.
     *
     * @param normalizedUrl canonical effective-upstream policy URL.
     * @param identity normalized optional user and group identities.
     * @param phase explicit request phase.
     * @param attributes provider-neutral protocol-derived attributes.
     * @return immutable context or a safe typed failure.
     */
    fun assemble(
        normalizedUrl: NormalizedPolicyUrl?,
        identity: NormalizedIdentity?,
        phase: PolicyPhase?,
        attributes: NormalizedProtocolAttributes?,
    ): PolicyContextAssemblyResult =
        when {
            normalizedUrl == null ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.MISSING_CONTEXT_INPUT)

            identity == null ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.MISSING_CONTEXT_INPUT)

            phase == null ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.MISSING_CONTEXT_INPUT)

            attributes == null ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.MISSING_CONTEXT_INPUT)

            phase != PolicyPhase.REQUEST ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.CONTRADICTORY_PHASE)

            !normalizedUrl.isCanonical ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.INVALID_NORMALIZED_URL)

            !identity.isCanonical ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.INVALID_NORMALIZED_IDENTITY)

            attributes.model.isBlank() ->
                PolicyContextAssemblyResult.Failure(PolicyContextAssemblyErrorCode.INVALID_PROTOCOL_ATTRIBUTES)

            else ->
                PolicyContextAssemblyResult.Success(
                    PolicyContext(
                        url = normalizedUrl.value,
                        model = attributes.model,
                        phase = phase,
                        user = identity.user,
                        groups = identity.groups,
                    ),
                )
        }
}

/** Checks one candidate against the normalized lowercase ASCII identity-token grammar. */
private fun String.isNormalizedIdentityToken(): Boolean = NORMALIZED_IDENTITY_TOKEN.matches(this)

/** Canonical lowercase ASCII identity token grammar. */
private val NORMALIZED_IDENTITY_TOKEN = Regex("[a-z0-9][a-z0-9._:@/\\-]{0,127}")
