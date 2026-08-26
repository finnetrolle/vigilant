package io.vigilant.gateway.metrics

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.vigilant.gateway.GatewayTestFixture
import java.time.Duration
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * Verifies proxy traffic metrics end to end through real Armeria gateway and
 * upstream servers while collecting observations through the OTel SDK reader.
 */
class MetricsServiceTest {
    private val fixture = GatewayTestFixture()
    private val reader = TestMetricReader()
    private val meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(reader)
        .build()
    private val meter = meterProvider.get("io.vigilant.gateway.test")

    /** Releases servers and the metrics SDK after every scenario. */
    @AfterTest
    fun tearDown() {
        fixture.close()
        meterProvider.close()
    }

    /**
     * A completed successful exchange contributes exactly one safe observation
     * to the request, response-status, upstream-latency, and gateway-latency metrics.
     */
    @Test
    fun `successful request records request status and latency metrics without secrets`() {
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.PLAIN_TEXT_UTF_8)
                    .build(),
                HttpData.ofUtf8("response body-secret-3A7D"),
            )
        }
        val gateway = fixture.startMetricsGateway(fixture.serverUri(upstream), meter)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.get("/v1/models?token=query-secret-9B2E").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val metrics = awaitMetrics()
        assertEquals(1L, metrics.singleMetric("vigilant.proxy.requests").singleLongSum())

        val statusPoint = metrics.singleMetric("vigilant.proxy.responses").longSumData.points.single()
        assertEquals(1L, statusPoint.value)
        assertEquals("2xx", statusPoint.attributes.get(stringKey("http.response.status_class")))

        listOf(
            "vigilant.proxy.upstream.duration",
            "vigilant.proxy.gateway.duration",
        ).forEach { name ->
            val metric = metrics.singleMetric(name)
            val point = metric.histogramData.points.single()
            assertEquals("s", metric.unit)
            assertEquals(1L, point.count)
            assertTrue(point.sum >= 0.0, "$name must record a non-negative duration")
        }

        metrics.forEach { metric ->
            metric.data.points.forEach { point ->
                point.attributes.forEach { _, value ->
                    assertFalse(value.toString().contains("query-secret-9B2E"))
                    assertFalse(value.toString().contains("body-secret-3A7D"))
                }
            }
        }
    }

    /**
     * The active-request gauge reflects an in-flight streaming exchange and
     * returns to zero only after that exchange completes.
     */
    @Test
    fun `active requests gauge tracks an in flight streaming exchange`() {
        val upstreamStarted = CountDownLatch(1)
        val finishUpstream = CountDownLatch(1)
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                thread(name = "metrics-active-upstream") {
                    response.write(ResponseHeaders.of(HttpStatus.OK))
                    upstreamStarted.countDown()
                    finishUpstream.await(5, TimeUnit.SECONDS)
                    response.close()
                }
            }
        }
        val gateway = fixture.startMetricsGateway(fixture.serverUri(upstream), meter)
        val responseFuture = WebClient.of(fixture.serverUri(gateway).toString())
            .get("/v1/stream")
            .aggregate()

        assertTrue(upstreamStarted.await(5, TimeUnit.SECONDS), "the upstream request never started")
        assertEquals(
            1L,
            reader.collectAllMetrics()
                .singleMetric("vigilant.proxy.active_requests")
                .longGaugeData.points.single().value,
        )

        finishUpstream.countDown()
        assertEquals(HttpStatus.OK, responseFuture.join().status())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                reader.collectAllMetrics()
                    .singleMetric("vigilant.proxy.active_requests")
                    .longGaugeData.points.single().value == 0L
            },
            "active requests did not return to zero after exchange completion",
        )
    }

    /** Correct upstream client and server errors pass through with bounded status metrics. */
    @Test
    fun `upstream 4xx and 5xx responses increment their status classes`() {
        val upstream = fixture.startServer { request ->
            when (request.path()) {
                "/client-error" -> HttpResponse.of(
                    HttpStatus.BAD_REQUEST,
                    MediaType.PLAIN_TEXT_UTF_8,
                    "client-error-body",
                )
                "/server-error" -> HttpResponse.of(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    MediaType.PLAIN_TEXT_UTF_8,
                    "server-error-body",
                )
                else -> HttpResponse.of(HttpStatus.NOT_FOUND)
            }
        }
        val gateway = fixture.startMetricsGateway(fixture.serverUri(upstream), meter)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val clientError = client.get("/client-error").aggregate().join()
        val serverError = client.get("/server-error").aggregate().join()

        assertEquals(HttpStatus.BAD_REQUEST, clientError.status())
        assertEquals("client-error-body", clientError.contentUtf8())
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, serverError.status())
        assertEquals("server-error-body", serverError.contentUtf8())

        val statusPoints = awaitMetric("vigilant.proxy.responses")
            .longSumData.points
            .associate { point ->
                assertNotNull(
                    point.attributes.get(stringKey("http.response.status_class")),
                    "every response point must carry a status class",
                ) to point.value
            }
        assertEquals(mapOf("4xx" to 1L, "5xx" to 1L), statusPoints)
    }

    /** A timed-out upstream exchange increments only the dedicated timeout counter. */
    @Test
    fun `upstream response timeout increments timeout metric`() {
        val upstream = fixture.startServer { HttpResponse.streaming() }
        val upstreamClient = WebClient.builder()
            .responseTimeout(Duration.ofMillis(200))
            .build()
        val gateway = fixture.startMetricsGateway(fixture.serverUri(upstream), meter, upstreamClient)

        val response = WebClient.of(fixture.serverUri(gateway).toString())
            .get("/v1/models")
            .aggregate()
            .join()

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.status())
        assertEquals(
            1L,
            awaitMetric("vigilant.proxy.timeouts").singleLongSum(),
        )
        assertFalse(reader.collectAllMetrics().any { it.name == "vigilant.proxy.transport_errors" })
    }

    /** A timeout after response headers still contributes to the timeout counter. */
    @Test
    fun `mid response upstream timeout increments timeout metric`() {
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                response.write(ResponseHeaders.of(HttpStatus.OK))
                response.write(HttpData.ofUtf8("partial"))
            }
        }
        val upstreamClient = WebClient.builder()
            .responseTimeout(Duration.ofMillis(200))
            .build()
        val gateway = fixture.startMetricsGateway(fixture.serverUri(upstream), meter, upstreamClient)

        val exchange = runCatching {
            WebClient.of(fixture.serverUri(gateway).toString())
                .get("/v1/stream")
                .aggregate()
                .join()
        }

        assertTrue(exchange.isFailure, "a timed-out response stream must fail the exchange")
        assertEquals(1L, awaitMetric("vigilant.proxy.timeouts").singleLongSum())
    }

    /** A transport failure after response headers still contributes to its counter. */
    @Test
    fun `mid response upstream failure increments transport error metric`() {
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                response.write(ResponseHeaders.of(HttpStatus.OK))
                response.write(HttpData.ofUtf8("partial"))
                response.close(IllegalStateException("upstream stream failed"))
            }
        }
        val gateway = fixture.startMetricsGateway(fixture.serverUri(upstream), meter)

        val exchange = runCatching {
            WebClient.of(fixture.serverUri(gateway).toString())
                .get("/v1/stream")
                .aggregate()
                .join()
        }

        assertTrue(exchange.isFailure, "a failed response stream must fail the exchange")
        val point = awaitMetric("vigilant.proxy.transport_errors").longSumData.points.single()
        assertEquals(1L, point.value)
        assertTrue(
            assertNotNull(point.attributes.get(stringKey("error.type"))).endsWith("Exception"),
            "error.type must remain a safe exception class",
        )
    }

    /** A connection failure increments only the transport-error counter with its safe class name. */
    @Test
    fun `dead upstream increments transport error metric with cause class`() {
        val deadUpstream = ServerSocket(0).use { socket ->
            java.net.URI.create("http://127.0.0.1:${socket.localPort}")
        }
        val gateway = fixture.startMetricsGateway(deadUpstream, meter)

        val response = WebClient.of(fixture.serverUri(gateway).toString())
            .get("/v1/models?token=query-secret-7F4C")
            .aggregate()
            .join()

        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        val point = awaitMetric("vigilant.proxy.transport_errors")
            .longSumData.points.single()
        assertEquals(1L, point.value)
        val errorType = assertNotNull(point.attributes.get(stringKey("error.type")))
        assertTrue(errorType.endsWith("Exception"), "error.type must be an exception class: $errorType")
        assertFalse(errorType.contains("query-secret-7F4C"))
        assertFalse(reader.collectAllMetrics().any { it.name == "vigilant.proxy.timeouts" })
    }

    /** A client abort increments cancellation without misclassifying it as an upstream failure. */
    @Test
    fun `client abort increments cancellation metric`() {
        val upstreamCancelled = CountDownLatch(1)
        val upstream = fixture.startServer(
            com.linecorp.armeria.server.HttpService { ctx, _ ->
                val response = HttpResponse.streaming()
                ctx.whenRequestCancelled().thenRun { upstreamCancelled.countDown() }
                thread(name = "metrics-cancellation-upstream") {
                    response.write(ResponseHeaders.of(HttpStatus.OK))
                    response.write(HttpData.ofUtf8("first chunk"))
                }
                response
            },
        )
        val gateway = fixture.startMetricsGateway(fixture.serverUri(upstream), meter)
        val clientCancelled = CountDownLatch(1)

        WebClient.of(fixture.serverUri(gateway).toString()).get("/v1/stream").subscribe(
            object : Subscriber<HttpObject> {
                private lateinit var subscription: Subscription

                /** Requests the streamed response without buffering it. */
                override fun onSubscribe(subscription: Subscription) {
                    this.subscription = subscription
                    subscription.request(Long.MAX_VALUE)
                }

                /** Cancels after the first body chunk reaches the client. */
                override fun onNext(item: HttpObject) {
                    if (item is HttpData) {
                        subscription.cancel()
                        clientCancelled.countDown()
                    }
                }

                /** Marks the client exchange terminal if cancellation surfaces as an error. */
                override fun onError(cause: Throwable) {
                    clientCancelled.countDown()
                }

                /** Marks the client exchange terminal if it completes before explicit cancellation. */
                override fun onComplete() {
                    clientCancelled.countDown()
                }
            },
        )

        assertTrue(clientCancelled.await(5, TimeUnit.SECONDS), "the client never received the stream")
        assertTrue(upstreamCancelled.await(5, TimeUnit.SECONDS), "cancellation did not reach the upstream")
        assertEquals(
            1L,
            awaitMetric("vigilant.proxy.cancellations").singleLongSum(),
        )
        assertFalse(reader.collectAllMetrics().any { it.name == "vigilant.proxy.timeouts" })
        assertFalse(reader.collectAllMetrics().any { it.name == "vigilant.proxy.transport_errors" })
    }

    /** Waits until all metrics produced by a completed request are collectable. */
    private fun awaitMetrics(): Collection<MetricData> {
        var metrics = reader.collectAllMetrics()
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                metrics = reader.collectAllMetrics()
                REQUIRED_SUCCESS_METRICS.all { required -> metrics.any { it.name == required } }
            },
            "the completed exchange did not produce all required metrics: ${metrics.map { it.name }}",
        )
        return metrics
    }

    /** Waits for one asynchronously published completion metric and returns it. */
    private fun awaitMetric(name: String): MetricData {
        var metric: MetricData? = null
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                metric = reader.collectAllMetrics().singleOrNull { it.name == name }
                metric != null
            },
            "the completed exchange did not produce $name",
        )
        return assertNotNull(metric)
    }

    private companion object {
        val REQUIRED_SUCCESS_METRICS = setOf(
            "vigilant.proxy.requests",
            "vigilant.proxy.responses",
            "vigilant.proxy.upstream.duration",
            "vigilant.proxy.gateway.duration",
        )
    }
}

/** Returns the only collected metric named [name]. */
private fun Collection<MetricData>.singleMetric(name: String): MetricData =
    singleOrNull { it.name == name }.also { assertNotNull(it, "missing metric $name") }!!

/** Returns the only long sum point value of this metric. */
private fun MetricData.singleLongSum(): Long = longSumData.points.single().value
