package io.vigilant.gateway.metrics

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.RawHttp1TestUpstream
import io.vigilant.gateway.assertUpstreamFailureWarning
import io.vigilant.gateway.proxy.BypassProxyService
import io.vigilant.gateway.renderForSecretScan
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E evidence that malformed upstream HTTP before response headers follows
 * the same stable and secret-safe transport-failure contract as connection
 * errors.
 */
class MalformedUpstreamResponseTest {
    private val fixture = GatewayTestFixture()
    private val reader = TestMetricReader()
    private val meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(reader)
        .build()
    private val meter = meterProvider.get("io.vigilant.gateway.malformed-upstream-test")
    private val upstreams = mutableListOf<RawHttp1TestUpstream>()

    /** Closes gateways, raw sockets, and the metrics SDK after every scenario. */
    @AfterTest
    fun closeResources() {
        fixture.close()
        upstreams.asReversed().forEach(RawHttp1TestUpstream::close)
        meterProvider.close()
    }

    /**
     * A malformed status line becomes a stable 502 while telemetry records only
     * bounded failure metadata and never the offending wire bytes or query.
     */
    @Test
    fun `malformed upstream status line becomes safe observable transport failure`() {
        val upstream = RawHttp1TestUpstream(
            diagnosticName = "malformed",
            applicationResponse = MALFORMED_HTTP_RESPONSE,
        ).also(upstreams::add)
        val logEvents = fixture.attachAppenderTo(BypassProxyService::class.java)
        val armeriaEvents = fixture.attachAppenderTo(ARMERIA_LOGGER_NAME)
        val gateway = fixture.startMetricsGateway(upstream.uri, meter)
        val client = WebClient.builder(fixture.serverUri(gateway).toString())
            .responseTimeout(Duration.ofSeconds(5))
            .build()

        val response = client.get("/v1/models?token=$REQUEST_SENTINEL").aggregate().join()

        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        assertEquals("""{"error":"upstream_unavailable"}""", response.contentUtf8())
        assertFalse(response.contentUtf8().contains(MALFORMED_WIRE_SENTINEL))

        var observedMetricTypes = emptyList<String>()
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                observedMetricTypes = reader.collectAllMetrics()
                    .singleOrNull { it.name == TRANSPORT_ERROR_METRIC }
                    ?.longSumData
                    ?.points
                    ?.mapNotNull { it.attributes.get(stringKey("error.type")) }
                    .orEmpty()
                observedMetricTypes.isNotEmpty() && logEvents.isNotEmpty()
            },
            "malformed upstream did not publish transport telemetry; error types=$observedMetricTypes",
        )

        val metric = reader.collectAllMetrics().single { it.name == TRANSPORT_ERROR_METRIC }
        val metricPoint = metric.longSumData.points.single()
        assertEquals(1L, metricPoint.value)
        val errorType = assertNotNull(metricPoint.attributes.get(stringKey("error.type")))
        assertTrue(errorType.endsWith("Exception"), "error.type must remain a safe exception class")

        val event = logEvents.single()
        event.assertUpstreamFailureWarning("upstream_unavailable")

        val telemetry = (logEvents + armeriaEvents).joinToString(separator = " ") { loggedEvent ->
            loggedEvent.renderForSecretScan()
        } + " $errorType"
        listOf(REQUEST_SENTINEL, MALFORMED_WIRE_SENTINEL).forEach { sentinel ->
            assertFalse(telemetry.contains(sentinel), "telemetry leaked a malformed-exchange sentinel")
        }
    }

    private companion object {
        const val TRANSPORT_ERROR_METRIC = "vigilant.proxy.transport_errors"
        const val ARMERIA_LOGGER_NAME = "com.linecorp.armeria"
        const val REQUEST_SENTINEL = "request-query-secret-7E2B"
        const val MALFORMED_WIRE_SENTINEL = "malformed-wire-secret-4A91"
        const val MALFORMED_HTTP_RESPONSE =
            "HTTP/1.1 20X $MALFORMED_WIRE_SENTINEL\r\n\r\n"
    }
}
