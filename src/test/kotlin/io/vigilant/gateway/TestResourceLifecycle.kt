package io.vigilant.gateway

/**
 * Runs every test-resource cleanup action in its ownership-defined order,
 * rethrowing the first failure with later failures suppressed.
 */
internal fun closeAllResources(vararg closeActions: () -> Unit) {
    var firstFailure: Throwable? = null
    closeActions.forEach { closeAction ->
        try {
            closeAction()
        } catch (cleanupFailure: Throwable) {
            val priorFailure = firstFailure
            if (priorFailure == null) {
                firstFailure = cleanupFailure
            } else if (priorFailure !== cleanupFailure) {
                priorFailure.addSuppressed(cleanupFailure)
            }
        }
    }
    firstFailure?.let { throw it }
}
