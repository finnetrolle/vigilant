package io.vigilant.policy.domain

/** Requires a blocking [disposition] to carry no [transformations]. */
internal fun requireNoBlockingTransformations(
    disposition: Disposition,
    transformations: Collection<*>,
    owner: String,
) {
    require(disposition != Disposition.BLOCK || transformations.isEmpty()) {
        "A blocking $owner cannot contain transformations"
    }
}
