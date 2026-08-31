package io.vigilant.audit

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * Transfers one record to the store and waits for its force-backed terminal result.
 *
 * This blocking bridge belongs only on a blocking-safe inspection or shutdown worker;
 * event-loop callers must schedule it and consume their result asynchronously.
 *
 * @return `null` after durable acceptance, otherwise the stable store failure.
 */
internal fun awaitDurableAudit(record: AuditRecord, reservation: AuditReservation): AuditStoreOutcomeCode? =
    when (val submission = reservation.submit(record)) {
        is AuditSubmissionResult.Rejected -> submission.code
        is AuditSubmissionResult.Accepted ->
            try {
                when (val result = submission.durable.get()) {
                    is AuditAppendResult.Durable -> null
                    is AuditAppendResult.Failed -> result.code
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Durable audit wait was cancelled").also {
                    it.initCause(interrupted)
                }
            } catch (_: ExecutionException) {
                AuditStoreOutcomeCode.IO_FAILURE
            }
    }
