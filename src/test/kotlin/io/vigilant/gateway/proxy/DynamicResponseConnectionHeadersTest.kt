package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpStatus
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.RawHttp1TestUpstream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Wire-level E2E evidence that response headers named by `Connection` remain
 * hop-by-hop across the upstream, gateway, and client exchange.
 */
class DynamicResponseConnectionHeadersTest {
    private val fixture = GatewayTestFixture()
    private val upstreams = mutableListOf<RawHttp1TestUpstream>()

    /** Closes the gateway and raw HTTP/1.1 upstream after every scenario. */
    @AfterTest
    fun closeResources() {
        fixture.close()
        upstreams.asReversed().forEach(RawHttp1TestUpstream::close)
    }

    /**
     * Multiple mixed-case `Connection` tokens remove only their named response
     * headers while preserving the status, body, and an end-to-end neighbor.
     */
    @Test
    fun `connection named response headers do not reach the client`() {
        val upstream = RawHttp1TestUpstream(
            diagnosticName = "dynamic-response-headers",
            applicationResponse = DYNAMIC_HEADER_RESPONSE,
        ).also(upstreams::add)
        val gateway = fixture.startServer(
            BypassProxyService(upstream.uri, WebClient.of()),
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.CREATED, response.status())
        assertEquals("upstream-body", response.contentUtf8())
        assertEquals("preserved", response.headers().get("x-keep"))
        assertFalse(response.headers().contains(HttpHeaderNames.CONNECTION))
        assertFalse(response.headers().contains("x-remove"))
        assertFalse(response.headers().contains("x-remove-too"))
    }

    private companion object {
        val DYNAMIC_HEADER_RESPONSE = buildString {
            append("HTTP/1.1 201 Created\r\n")
            append("Content-Length: 13\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Connection: keep-alive, X-ReMoVe\r\n")
            append("Connection: x-remove-too\r\n")
            append("X-Remove: discarded\r\n")
            append("X-Remove-Too: discarded-too\r\n")
            append("X-Keep: preserved\r\n")
            append("\r\n")
            append("upstream-body")
        }
    }
}
