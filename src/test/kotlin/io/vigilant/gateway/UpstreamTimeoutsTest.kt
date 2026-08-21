package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.Server
import java.net.URI
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E2E tests of the configured upstream client timeouts: the gateway is wired
 * exactly as in production (environment -> [loadAppConfig] ->
 * [buildUpstreamWebClient]) and proxy behavior is observed through real Armeria
 * servers (spec v0: explicit upstream timeouts safe for long LLM streams).
 */
class UpstreamTimeoutsTest {
    private val servers = mutableListOf<Server>()

    @AfterTest
    fun stopServers() {
        servers.asReversed().forEach { it.stop().join() }
    }

    @Test
    fun `hung upstream is interrupted by env-configured response timeout with stable proxy error`() {
        val hungUpstream = startServer { HttpResponse.streaming() }
        val gateway = startGateway(hungUpstream) {
            put("VIGILANT_UPSTREAM_RESPONSE_TIMEOUT", "300ms")
        }
        val client = clientOf(gateway)

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.status())
        assertEquals("""{"error":"upstream_timeout"}""", response.contentUtf8())
    }

    @Test
    fun `slow chunked stream outlives the configured response timeout`() {
        val chunkCount = 6
        val chunkIntervalMillis = 250L
        val upstream = startServer {
            val streaming = HttpResponse.streaming()
            thread {
                streaming.write(ResponseHeaders.of(HttpStatus.OK))
                repeat(chunkCount) { index ->
                    Thread.sleep(chunkIntervalMillis)
                    streaming.write(HttpData.ofUtf8("chunk$index\n"))
                }
                streaming.close()
            }
            streaming
        }
        val gateway = startGateway(upstream) {
            put("VIGILANT_UPSTREAM_RESPONSE_TIMEOUT", "300ms")
        }
        val client = clientOf(gateway)

        val response = client.get("/v1/messages").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(
            (0 until chunkCount).joinToString("") { "chunk$it\n" },
            response.contentUtf8(),
        )
    }

    @Test
    fun `delayed first byte survives a shorter connection idle timeout`() {
        val upstream = startServer {
            val streaming = HttpResponse.streaming()
            thread {
                Thread.sleep(800)
                streaming.write(ResponseHeaders.of(HttpStatus.OK))
                streaming.write(HttpData.ofUtf8("late"))
                streaming.close()
            }
            streaming
        }
        val gateway = startGateway(upstream) {
            put("VIGILANT_UPSTREAM_CONNECTION_IDLE_TIMEOUT", "200ms")
        }
        val client = clientOf(gateway)

        val response = client.get("/v1/messages").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("late", response.contentUtf8())
    }

    private fun clientOf(gateway: Server): WebClient =
        WebClient.builder(serverUri(gateway).toString())
            .responseTimeout(Duration.ofSeconds(10))
            .build()

    /**
     * Starts the gateway against the given upstream, with the upstream client
     * derived from the configuration that the given extra environment produces.
     */
    private fun startGateway(upstream: Server, extraEnv: MutableMap<String, String>.() -> Unit = {}): Server {
        val env = mutableMapOf(
            "VIGILANT_UPSTREAM_URL" to serverUri(upstream).toString(),
        ).apply(extraEnv)
        val config = loadAppConfig(env = env, defaultConfigPaths = emptyList())
        return Server.builder()
            .http(0)
            .serviceUnder("/", BypassProxyService(config.upstreamUri, buildUpstreamWebClient(config.upstream)))
            .build()
            .startAndTrack()
    }

    private fun startServer(service: (HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    private fun Server.startAndTrack(): Server {
        start().join()
        servers += this
        return this
    }

    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")
}
