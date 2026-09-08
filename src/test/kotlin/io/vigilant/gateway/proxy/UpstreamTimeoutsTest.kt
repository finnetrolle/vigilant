package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.Server
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.config.loadAppConfig
import io.vigilant.gateway.loopbackHttpAddress
import io.vigilant.gateway.startWithinTestTimeout
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E tests of the configured upstream client timeouts: the gateway is wired
 * exactly as in production (environment -> [loadAppConfig] ->
 * [buildUpstreamWebClient]) and proxy behavior is observed through real Armeria
 * servers (spec v0: explicit upstream timeouts safe for long LLM streams).
 */
class UpstreamTimeoutsTest {
    private val servers = mutableListOf<Server>()
    private val upstreamClientFactories = mutableListOf<ClientFactory>()
    private val gatewayClientFactory = ClientFactory.builder().build()

    /** Boundedly stops servers before all instance-owned upstream and gateway client factories. */
    @AfterTest
    fun stopServers() {
        val closeActions = buildList<() -> Unit> {
            servers.asReversed().forEach { server ->
                add { server.closeAsync().get(RESOURCE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
            }
            upstreamClientFactories.asReversed().forEach { factory ->
                add { factory.closeAsync().get(RESOURCE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
            }
            add { gatewayClientFactory.closeAsync().get(RESOURCE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
        }
        closeAllResources(*closeActions.toTypedArray())
        assertTrue(servers.all(Server::isClosed), "every timeout fixture server must be closed")
        assertTrue(upstreamClientFactories.all(ClientFactory::isClosed), "every upstream pool must be closed")
        assertTrue(gatewayClientFactory.isClosed, "the gateway client pool must be closed")
    }

    /** Enforces the configured response deadline for an upstream that never responds. */
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

    /** Keeps a progressing chunked stream alive beyond the response timeout duration. */
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

    /** Allows a delayed first byte despite a shorter connection-pool idle timeout. */
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

    /** Builds a gateway-facing client on the instance-owned isolated transport. */
    private fun clientOf(gateway: Server): WebClient =
        WebClient.builder(serverUri(gateway).toString())
            .factory(gatewayClientFactory)
            .responseTimeout(Duration.ofSeconds(10))
            .build()

    /**
     * Starts the gateway against the given upstream, with the upstream client
     * derived from the configuration that the given extra environment produces.
     */
    private fun startGateway(upstream: Server, extraEnv: MutableMap<String, String>.() -> Unit = {}): Server {
        val env = mutableMapOf(
            "VIGILANT_UPSTREAM_URL" to serverUri(upstream).toString(),
            "VIGILANT_ENVIRONMENT" to "test",
            "VIGILANT_IDENTITY_MODE" to "DUMMY",
            "VIGILANT_IDENTITY_DUMMY_USER" to "timeout-test-user",
        ).apply(extraEnv)
        val config = loadAppConfig(env = env, defaultConfigPaths = emptyList())
        val factory = buildUpstreamClientFactory(config.upstream).also(upstreamClientFactories::add)
        return Server.builder()
            .http(loopbackHttpAddress())
            .serviceUnder("/", BypassProxyService(config.upstreamUri, buildUpstreamWebClient(config.upstream, factory)))
            .build()
            .startAndTrack()
    }

    /** Starts an ephemeral loopback upstream and registers it for bounded cleanup. */
    private fun startServer(service: (HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(loopbackHttpAddress())
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    /** Starts and registers this server for reverse-order bounded cleanup. */
    private fun Server.startAndTrack(): Server {
        servers += this
        startWithinTestTimeout()
        return this
    }

    /** Returns the loopback URI for a fixture-owned started server. */
    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")

    private companion object {
        /** Maximum wait for each owned timeout fixture resource to close. */
        val RESOURCE_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
