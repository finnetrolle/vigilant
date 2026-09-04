package io.vigilant.gateway

import io.vigilant.lifecycle.runAllCleanupActions

/**
 * Runs every test-resource cleanup action in its ownership-defined order,
 * rethrowing the first failure with later failures suppressed.
 */
internal fun closeAllResources(vararg closeActions: () -> Unit) {
    runAllCleanupActions(*closeActions)
}
