package io.vigilant.gateway.identity

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.RequestOptions
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.ContentTooLargeException
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.TimeoutException
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.vigilant.context.MAX_NORMALIZED_IDENTITY_GROUPS
import io.vigilant.context.NormalizedIdentity
import io.vigilant.context.normalizeIdentityTokenOrNull
import io.vigilant.gateway.config.ExternalIdentitySettings
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * Owns trusted Bridge HTTP lookup protocol, admission, deadline, parsing, and observations.
 *
 * @param settings immutable endpoint and timeout snapshot.
 * @param webClient client backed by the application-owned shared factory.
 * @param timeoutScheduler scheduler owned by that same shared factory.
 * @param maxConcurrentLookups immediate-admission permit count.
 * @param meter process telemetry meter.
 * @param tracer process telemetry tracer.
 */
internal class BridgeIdentityClient(
    private val settings: ExternalIdentitySettings,
    private val webClient: WebClient,
    private val timeoutScheduler: ScheduledExecutorService,
    maxConcurrentLookups: Int,
    meter: Meter,
    tracer: Tracer,
) : ExternalIdentityLookup,
    AutoCloseable {
    private val permits = Semaphore(maxConcurrentLookups)
    private val active = ConcurrentHashMap.newKeySet<BridgeLookupOperation>()
    private val closed = AtomicBoolean()
    private val lifecycleLock = Any()
    private val lookupCounter: LongCounter =
        meter.counterBuilder("vigilant.identity.external.lookups")
            .setDescription("Number of completed External identity lookups by finite outcome")
            .setUnit("{lookup}")
            .build()
    private val lookupDuration: DoubleHistogram =
        meter.histogramBuilder("vigilant.identity.external.lookup.duration")
            .setDescription("Complete duration of External identity lookups")
            .setUnit("s")
            .build()
    private val tracer = tracer
    private val requestOptions =
        RequestOptions.builder()
            .responseTimeout(settings.timeout)
            .writeTimeout(settings.timeout)
            .build()

    /** Starts one immediate-admission asynchronous Bridge exchange. */
    @Suppress("ReturnCount")
    override fun lookup(token: String): CompletableFuture<ExternalIdentityLookupResult> {
        val observation = LookupObservation()
        if (closed.get()) return cancelledFuture(observation)
        if (!permits.tryAcquire()) {
            observation.finish(OUTCOME_OVERLOADED, error = true)
            return CompletableFuture.completedFuture(
                ExternalIdentityLookupResult.Unavailable(ExternalIdentityFailureCode.OVERLOADED),
            )
        }

        val operation = BridgeLookupOperation(observation)
        synchronized(lifecycleLock) {
            active += operation
            if (closed.get()) {
                operation.cancel(false)
                return operation
            }

            try {
                val response = webClient.execute(identityRequest(token), requestOptions)
                    .peekHeaders { headers -> operation.observeStatus(headers.status().code()) }
                val aggregation = response.aggregate()
                operation.install(response, aggregation)
                operation.installTimeout(
                    timeoutScheduler.schedule(
                        operation::timeout,
                        settings.timeout.toNanos(),
                        TimeUnit.NANOSECONDS,
                    ),
                )
                aggregation.whenComplete { aggregate, failure ->
                    if (failure != null) {
                        operation.completeUnavailable(failureCode(failure))
                    } else {
                        operation.completeResult(parseResponse(aggregate))
                    }
                }
            } catch (_: Exception) {
                operation.abortUnavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR)
            }
        }
        return operation
    }

    /** Cancels every active lookup before the shared application client factory is closed. */
    override fun close() {
        val operations =
            synchronized(lifecycleLock) {
                if (!closed.compareAndSet(false, true)) return
                active.toList()
            }
        operations.forEach { operation -> operation.cancel(false) }
    }

    /** Builds the exact zero-body POST without copying any inbound request context. */
    @Suppress("SpreadOperator")
    private fun identityRequest(token: String): HttpRequest {
        val endpoint = settings.endpoint
        val path = (endpoint.rawPath.takeUnless(String::isNullOrEmpty) ?: "/") +
            endpoint.rawQuery?.let { query -> "?$query" }.orEmpty()
        val headers =
            RequestHeaders.builder(HttpMethod.POST, path)
                .scheme(endpoint.scheme)
                .authority(endpoint.rawAuthority)
                .set(HttpHeaderNames.AUTHORIZATION, "Bearer $token")
                .set(HttpHeaderNames.ACCEPT, MediaType.JSON.toString())
                .set(HttpHeaderNames.CONTENT_LENGTH, "0")
                .build()
        return HttpRequest.of(headers, *emptyArray<HttpData>())
    }

    /** Maps one complete response into the closed success-or-safe-failure contract. */
    @Suppress("ReturnCount")
    private fun parseResponse(response: AggregatedHttpResponse): ExternalIdentityLookupResult {
        if (response.status() != HttpStatus.OK) {
            return unavailable(ExternalIdentityFailureCode.PROVIDER_STATUS)
        }
        if (response.contentType()?.withoutParameters() != MediaType.JSON) {
            return unavailable(ExternalIdentityFailureCode.INVALID_RESPONSE)
        }
        return try {
            val root = IDENTITY_JSON.readTree(response.content().array())
            require(root.isObject)
            ExternalIdentityLookupResult.Resolved(parseIdentity(root))
        } catch (_: Exception) {
            unavailable(ExternalIdentityFailureCode.INVALID_RESPONSE)
        }
    }

    /** Validates and normalizes the exact required `user` and `groups` document members. */
    private fun parseIdentity(root: JsonNode): NormalizedIdentity {
        val userNode = root.get(USER_FIELD)
        val user = userNode?.takeIf(JsonNode::isTextual)?.textValue()?.normalizeIdentityTokenOrNull()
        requireNotNull(user)

        val groupsNode = root.get(GROUPS_FIELD)
        require(groupsNode != null && groupsNode.isArray)
        val groups = LinkedHashSet<String>()
        groupsNode.forEach { member ->
            val group = member.takeIf(JsonNode::isTextual)?.textValue()?.normalizeIdentityTokenOrNull()
            require(group != null && groups.add(group))
            require(groups.size <= MAX_NORMALIZED_IDENTITY_GROUPS)
        }
        return NormalizedIdentity(user, Collections.unmodifiableSet(groups))
    }

    /** Classifies one aggregate failure without retaining or exposing its details. */
    private fun failureCode(failure: Throwable): ExternalIdentityFailureCode =
        when (unwrapFailure(failure)) {
            is ContentTooLargeException -> ExternalIdentityFailureCode.INVALID_RESPONSE
            is TimeoutException -> ExternalIdentityFailureCode.TIMEOUT
            else -> ExternalIdentityFailureCode.TRANSPORT_ERROR
        }

    /** Removes asynchronous wrappers before finite internal classification. */
    private tailrec fun unwrapFailure(failure: Throwable): Throwable {
        val cause = failure.cause
        return if (cause != null && cause !== failure) unwrapFailure(cause) else failure
    }

    /** Creates one safe unavailable value. */
    private fun unavailable(code: ExternalIdentityFailureCode): ExternalIdentityLookupResult.Unavailable =
        ExternalIdentityLookupResult.Unavailable(code)

    /** Creates an already-cancelled lookup result for post-close calls. */
    private fun cancelledFuture(observation: LookupObservation): CompletableFuture<ExternalIdentityLookupResult> =
        CompletableFuture<ExternalIdentityLookupResult>().apply {
            observation.finish(OUTCOME_CANCELLED, error = false)
            cancel(false)
        }

    /** One credential-free terminal telemetry observation owned by one lookup call. */
    private inner class LookupObservation {
        private val startedNanos = System.nanoTime()
        private val finished = AtomicBoolean()
        private val statusClass = AtomicReference<String?>()
        private val span: Span =
            tracer.spanBuilder("vigilant.identity.external.lookup")
                .setParent(Context.current())
                .setSpanKind(SpanKind.CLIENT)
                .startSpan()
                .apply { setAttribute(IDENTITY_MODE, EXTERNAL_MODE) }

        /** Retains only the bounded class of received final Bridge response headers. */
        fun observeStatus(statusCode: Int) {
            if (statusCode in MIN_OBSERVED_HTTP_STATUS..MAX_OBSERVED_HTTP_STATUS) {
                statusClass.compareAndSet(null, "${statusCode / STATUS_CLASS_DIVISOR}xx")
            }
        }

        /** Emits the once-only counter, duration, and terminal span. */
        fun finish(outcome: String, error: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            val attributes =
                Attributes.builder()
                    .put(IDENTITY_MODE, EXTERNAL_MODE)
                    .put(IDENTITY_OUTCOME, outcome)
                    .apply { statusClass.get()?.let { put(HTTP_RESPONSE_STATUS_CLASS, it) } }
                    .build()
            lookupCounter.add(1, attributes)
            lookupDuration.record((System.nanoTime() - startedNanos) / NANOS_PER_SECOND, attributes)
            span.setAttribute(IDENTITY_OUTCOME, outcome)
            statusClass.get()?.let { span.setAttribute(HTTP_RESPONSE_STATUS_CLASS, it) }
            if (error) span.setStatus(StatusCode.ERROR)
            span.end()
        }
    }

    /** One permit-owning exchange whose terminal transition releases every owned reference once. */
    @Suppress("TooManyFunctions")
    private inner class BridgeLookupOperation(
        private val observation: LookupObservation,
    ) : CompletableFuture<ExternalIdentityLookupResult>() {
        private val terminal = AtomicBoolean()
        private val response = AtomicReference<HttpResponse?>()
        private val aggregation = AtomicReference<CompletableFuture<AggregatedHttpResponse>?>()
        private val timeoutTask = AtomicReference<ScheduledFuture<*>?>()

        /** Rejects caller-forged completion so only owned terminal transitions can publish results. */
        override fun complete(value: ExternalIdentityLookupResult): Boolean = false

        /** Rejects caller-forged exceptional completion outside the owned terminal state machine. */
        override fun completeExceptionally(exception: Throwable): Boolean = false

        /** Rejects caller-forged replacement of the owned terminal value. */
        override fun obtrudeValue(value: ExternalIdentityLookupResult) {
            throw UnsupportedOperationException(CALLER_COMPLETION_MESSAGE)
        }

        /** Rejects caller-forged replacement of the owned terminal failure. */
        override fun obtrudeException(exception: Throwable) {
            throw UnsupportedOperationException(CALLER_COMPLETION_MESSAGE)
        }

        /** Rejects caller-scheduled completion outside the owned terminal state machine. */
        override fun completeAsync(
            supplier: Supplier<out ExternalIdentityLookupResult>,
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(CALLER_COMPLETION_MESSAGE)

        /** Rejects caller-scheduled completion outside the owned terminal state machine. */
        override fun completeAsync(
            supplier: Supplier<out ExternalIdentityLookupResult>,
            executor: Executor,
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(CALLER_COMPLETION_MESSAGE)

        /** Rejects caller-owned timeout completion outside the Bridge deadline. */
        override fun orTimeout(
            timeout: Long,
            unit: TimeUnit,
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(CALLER_COMPLETION_MESSAGE)

        /** Rejects caller-owned fallback completion outside the Bridge deadline. */
        override fun completeOnTimeout(
            value: ExternalIdentityLookupResult,
            timeout: Long,
            unit: TimeUnit,
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(CALLER_COMPLETION_MESSAGE)

        /** Records only the finite response status class after final headers arrive. */
        fun observeStatus(statusCode: Int) {
            observation.observeStatus(statusCode)
        }

        /** Publishes the whole-exchange deadline task and cancels it after any earlier terminal event. */
        fun installTimeout(task: ScheduledFuture<*>) {
            timeoutTask.set(task)
            if (terminal.get()) task.cancel(false)
        }

        /** Publishes exchange handles and applies cancellation that won before publication. */
        fun install(
            activeResponse: HttpResponse,
            activeAggregation: CompletableFuture<AggregatedHttpResponse>,
        ) {
            response.set(activeResponse)
            aggregation.set(activeAggregation)
            if (terminal.get()) {
                aggregation.getAndSet(null)?.cancel(false)
                response.getAndSet(null)?.abort()
            }
        }

        /** Completes one finite unavailable outcome. */
        fun completeUnavailable(code: ExternalIdentityFailureCode) {
            completeResult(unavailable(code))
        }

        /** Publishes the sole successful or unavailable terminal result. */
        fun completeResult(result: ExternalIdentityLookupResult) {
            publishResult(result, cancelExchange = false)
        }

        /** Publishes startup failure after aborting any exchange handles already installed. */
        fun abortUnavailable(code: ExternalIdentityFailureCode) {
            publishResult(unavailable(code), cancelExchange = true)
        }

        /** Owns the common once-only result transition and optional exchange abortion. */
        private fun publishResult(
            result: ExternalIdentityLookupResult,
            cancelExchange: Boolean,
        ) {
            if (!terminal.compareAndSet(false, true)) return
            releaseOwnership(cancelExchange = cancelExchange)
            observation.finish(result.telemetryOutcome(), error = result is ExternalIdentityLookupResult.Unavailable)
            super.complete(result)
        }

        /** Cancels the active exchange and completes with the finite timeout result. */
        fun timeout() {
            if (!terminal.compareAndSet(false, true)) return
            releaseOwnership(cancelExchange = true)
            observation.finish(OUTCOME_TIMEOUT, error = true)
            super.complete(unavailable(ExternalIdentityFailureCode.TIMEOUT))
        }

        /** Cancels the exchange and publishes cancellation as its sole terminal state. */
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (!terminal.compareAndSet(false, true)) return false
            releaseOwnership(cancelExchange = true, mayInterruptIfRunning = mayInterruptIfRunning)
            observation.finish(OUTCOME_CANCELLED, error = false)
            return super.cancel(mayInterruptIfRunning)
        }

        /** Releases this operation's active-set membership and semaphore permit exactly once. */
        private fun releaseOwnership(
            cancelExchange: Boolean,
            mayInterruptIfRunning: Boolean = false,
        ) {
            timeoutTask.getAndSet(null)?.cancel(false)
            val activeAggregation = aggregation.getAndSet(null)
            val activeResponse = response.getAndSet(null)
            if (cancelExchange) {
                activeResponse?.abort()
                activeAggregation?.cancel(mayInterruptIfRunning)
            }
            active -= this
            permits.release()
        }
    }

    /** Converts one closed lookup result into its canonical credential-free telemetry value. */
    private fun ExternalIdentityLookupResult.telemetryOutcome(): String =
        when (this) {
            is ExternalIdentityLookupResult.Resolved -> OUTCOME_SUCCESS
            is ExternalIdentityLookupResult.Unavailable ->
                when (code) {
                    ExternalIdentityFailureCode.PROVIDER_STATUS -> OUTCOME_PROVIDER_STATUS
                    ExternalIdentityFailureCode.INVALID_RESPONSE -> OUTCOME_INVALID_RESPONSE
                    ExternalIdentityFailureCode.TIMEOUT -> OUTCOME_TIMEOUT
                    ExternalIdentityFailureCode.TRANSPORT_ERROR -> OUTCOME_TRANSPORT_ERROR
                    ExternalIdentityFailureCode.OVERLOADED -> OUTCOME_OVERLOADED
                }
        }

    private companion object {
        const val USER_FIELD = "user"
        const val GROUPS_FIELD = "groups"
        const val EXTERNAL_MODE = "EXTERNAL"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_PROVIDER_STATUS = "provider_status"
        const val OUTCOME_INVALID_RESPONSE = "invalid_response"
        const val OUTCOME_TIMEOUT = "timeout"
        const val OUTCOME_TRANSPORT_ERROR = "transport_error"
        const val OUTCOME_OVERLOADED = "overloaded"
        const val OUTCOME_CANCELLED = "cancelled"
        const val STATUS_CLASS_DIVISOR = 100
        const val MIN_OBSERVED_HTTP_STATUS = 200
        const val MAX_OBSERVED_HTTP_STATUS = 599
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val CALLER_COMPLETION_MESSAGE = "Lookup completion is owned by BridgeIdentityClient"
        val IDENTITY_MODE: AttributeKey<String> = AttributeKey.stringKey("identity.mode")
        val IDENTITY_OUTCOME: AttributeKey<String> = AttributeKey.stringKey("identity.outcome")
        val HTTP_RESPONSE_STATUS_CLASS: AttributeKey<String> =
            AttributeKey.stringKey("http.response.status_class")

        /** Strict duplicate-detecting parser for trusted Bridge identity documents. */
        val IDENTITY_JSON: ObjectMapper =
            ObjectMapper(
                JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build(),
            )
    }
}
