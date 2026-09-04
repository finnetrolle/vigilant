package io.vigilant.lifecycle

/**
 * Runs every terminal cleanup action in ownership order.
 *
 * The first failure is rethrown only after all actions have been attempted; distinct later
 * failures are attached to it as suppressed evidence.
 */
@Suppress("TooGenericExceptionCaught")
internal fun runAllCleanupActions(vararg actions: () -> Unit) {
    var firstFailure: Throwable? = null
    actions.forEach { action ->
        try {
            action()
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
