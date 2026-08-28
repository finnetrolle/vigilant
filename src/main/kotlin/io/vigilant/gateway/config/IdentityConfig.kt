package io.vigilant.gateway.config

import io.vigilant.gateway.identity.TrustedNetwork

/** Environment variable selecting the single identity extraction mode. */
private const val IDENTITY_MODE_ENV = "VIGILANT_IDENTITY_MODE"

/** Environment variable configuring the trusted user header name. */
private const val IDENTITY_USER_HEADER_ENV = "VIGILANT_IDENTITY_USER_HEADER"

/** Environment variable configuring the trusted groups header name. */
private const val IDENTITY_GROUPS_HEADER_ENV = "VIGILANT_IDENTITY_GROUPS_HEADER"

/** Environment variable configuring trusted immediate-peer CIDRs. */
private const val IDENTITY_TRUSTED_CIDRS_ENV = "VIGILANT_IDENTITY_TRUSTED_CIDRS"

/** Mutually exclusive sources from which Vigilant may derive request identity. */
enum class IdentityMode {
    /** No request identity is consumed. */
    ANONYMOUS,

    /** Identity is accepted from configured headers at a trusted immediate peer. */
    TRUSTED_HEADERS,

    /** Identity is consumed from HTTP Basic credentials. */
    BASIC,
}

/**
 * Validated identity extraction settings.
 *
 * @param mode the single enabled identity source.
 * @param userHeader canonical configured user header, when applicable.
 * @param groupsHeader canonical configured groups header, when applicable.
 * @param trustedNetworks parsed immediate-peer CIDRs trusted to supply configured headers.
 */
data class IdentitySettings(
    val mode: IdentityMode,
    val userHeader: String?,
    val groupsHeader: String?,
    val trustedNetworks: List<TrustedNetwork>,
)

/** Validates identity fields decoded as part of the complete application configuration. */
internal fun VigilantSettings.validatedIdentitySettings(): IdentitySettings =
    validatedIdentitySettings(identityMode, identityUserHeader, identityGroupsHeader, identityTrustedCidrs)

/** Validates the mutually exclusive identity source and its trust boundary. */
private fun validatedIdentitySettings(
    mode: String,
    userHeader: String?,
    groupsHeader: String?,
    trustedCidrs: List<String>,
): IdentitySettings {
    val identityMode = try {
        IdentityMode.valueOf(mode)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException(
            "$IDENTITY_MODE_ENV must be ANONYMOUS, TRUSTED_HEADERS, or BASIC",
            failure,
        )
    }
    val trustedNetworks = if (identityMode == IdentityMode.TRUSTED_HEADERS) {
        require(userHeader != null || groupsHeader != null) {
            "TRUSTED_HEADERS mode requires $IDENTITY_USER_HEADER_ENV or $IDENTITY_GROUPS_HEADER_ENV"
        }
        require(trustedCidrs.isNotEmpty()) {
            "$IDENTITY_TRUSTED_CIDRS_ENV must contain at least one CIDR in TRUSTED_HEADERS mode"
        }
        trustedCidrs.map { cidr ->
            requireNotNull(TrustedNetwork.parseOrNull(cidr)) {
                "$IDENTITY_TRUSTED_CIDRS_ENV must contain only literal IPv4 or IPv6 CIDRs"
            }
        }
    } else {
        require(userHeader == null) {
            "$IDENTITY_USER_HEADER_ENV is only valid in TRUSTED_HEADERS mode"
        }
        require(groupsHeader == null) {
            "$IDENTITY_GROUPS_HEADER_ENV is only valid in TRUSTED_HEADERS mode"
        }
        require(trustedCidrs.isEmpty()) {
            "$IDENTITY_TRUSTED_CIDRS_ENV is only valid in TRUSTED_HEADERS mode"
        }
        emptyList()
    }
    val canonicalUserHeader = userHeader?.let { validatedHeaderName(IDENTITY_USER_HEADER_ENV, it) }
    val canonicalGroupsHeader = groupsHeader?.let { validatedHeaderName(IDENTITY_GROUPS_HEADER_ENV, it) }
    require(canonicalUserHeader == null || canonicalUserHeader != canonicalGroupsHeader) {
        "$IDENTITY_USER_HEADER_ENV and $IDENTITY_GROUPS_HEADER_ENV must be distinct"
    }
    return IdentitySettings(identityMode, canonicalUserHeader, canonicalGroupsHeader, trustedNetworks)
}
