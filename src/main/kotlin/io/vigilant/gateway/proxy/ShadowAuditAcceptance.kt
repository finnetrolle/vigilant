package io.vigilant.gateway.proxy

import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.trace.Span
import io.vigilant.audit.AuditRecord
import io.vigilant.audit.AuditReservation
import io.vigilant.audit.AuditStoreOutcomeCode
import io.vigilant.audit.awaitDurableAudit

/**
 * Applies the single request-path terminal protocol for a prepared safe audit record.
 *
 * Durable acceptance always precedes the best-effort stdout projection. Projection failure never
 * changes an already durable result.
 *
 * @return `null` after durable acceptance, otherwise the stable store failure.
 */
internal fun acceptShadowAudit(
    serviceContext: ServiceRequestContext,
    record: AuditRecord,
    inspectionSpan: Span?,
    auditReservation: AuditReservation,
    auditLogger: ShadowAuditLogger,
): AuditStoreOutcomeCode? {
    val failure = awaitDurableAudit(record, auditReservation)
    if (failure == null) runCatching { auditLogger.emit(serviceContext, record, inspectionSpan) }
    return failure
}

/**
 * Builds and applies one supported-request ERROR through the canonical terminal audit protocol.
 *
 * @return `null` after durable acceptance, otherwise the stable store failure.
 */
internal fun acceptShadowAuditError(
    serviceContext: ServiceRequestContext,
    error: ShadowAuditError,
    inspectionSpan: Span?,
    auditReservation: AuditReservation,
    auditLogger: ShadowAuditLogger,
): AuditStoreOutcomeCode? =
    acceptShadowAudit(
        serviceContext = serviceContext,
        record = auditLogger.errorRecord(serviceContext, error),
        inspectionSpan = inspectionSpan,
        auditReservation = auditReservation,
        auditLogger = auditLogger,
    )
