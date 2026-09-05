package io.vigilant.gateway.identity

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.logging.RequestLogProperty
import io.netty.channel.ChannelOption
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.vigilant.gateway.DisconnectingTestUpstream
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.HoldingTestEndpoint
import io.vigilant.gateway.config.ExternalIdentitySettings
import io.vigilant.gateway.metrics.TestMetricReader
import java.net.URI
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Real-Armeria protocol tests for the trusted Bridge identity client. */
@Suppress("LargeClass")
class BridgeIdentityClientTest {
    private val fixture = GatewayTestFixture()

    /** Stops every real server after the scenario. */
    @AfterTest
    fun closeFixture() {
        fixture.close()
    }

    /** REQ-01 and OK-01: One exact empty POST resolves one normalized identity. */
    @Test
    fun `bridge success uses exact request and returns normalized identity`() {
        val observed = CompletableFuture<ObservedBridgeRequest>()
        val bridge =
            fixture.startServer { request ->
                HttpResponse.of(
                    request.aggregate().thenApply { aggregate ->
                        observed.complete(
                            ObservedBridgeRequest(
                                method = aggregate.method(),
                                path = aggregate.path(),
                                headers = aggregate.headers(),
                                bodyLength = aggregate.content().length(),
                            ),
                        )
                        HttpResponse.of(
                            HttpStatus.OK,
                            MediaType.JSON,
                            """{"user":"External.User","groups":["Operators","Security"]}""",
                        )
                    },
                )
            }
        val endpoint = URI("${fixture.serverUri(bridge)}/base/v1/identity?tenant=alpha%2Fbeta")
        val telemetry = OpenTelemetry.noop()
        val webClient = WebClient.of()
        val client =
            BridgeIdentityClient(
                settings = ExternalIdentitySettings(endpoint, Duration.ofSeconds(1)),
                webClient = webClient,
                timeoutScheduler = sharedTimeoutScheduler(webClient),
                maxConcurrentLookups = 2,
                meter = telemetry.getMeter("bridge-test"),
                tracer = telemetry.getTracer("bridge-test"),
            )

        val result = client.lookup("opaque-token.with+bytes").join()

        val request = observed.get(2, TimeUnit.SECONDS)
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/base/v1/identity?tenant=alpha%2Fbeta", request.path)
        assertEquals("Bearer opaque-token.with+bytes", request.headers.get("authorization"))
        assertEquals("application/json", request.headers.get("accept"))
        assertEquals("0", request.headers.get("content-length"))
        assertEquals(0, request.bodyLength)
        val identity = assertIs<ExternalIdentityLookupResult.Resolved>(result).identity
        assertEquals("external.user", identity.user)
        assertEquals(setOf("operators", "security"), identity.groups)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (identity.groups as MutableSet<String>).add("later")
        }
    }

    /** OK-02..05: Each allowed media, group, normalization, and additive field variant succeeds. */
    @TestFactory
    fun `valid bridge identity document variants succeed`(): List<DynamicTest> {
        val cases =
            listOf(
                BridgeResponseCase(
                    "OK-02 application-json-with-charset",
                    MediaType.JSON_UTF_8,
                    """{"user":"user","groups":["group"]}""".toByteArray(),
                    "user",
                    setOf("group"),
                ),
                BridgeResponseCase(
                    "OK-03 empty-groups",
                    MediaType.JSON,
                    """{"user":"user","groups":[]}""".toByteArray(),
                    "user",
                    emptySet(),
                ),
                BridgeResponseCase(
                    "OK-04 canonical-normalization",
                    MediaType.JSON,
                    """{"user":"User.Name","groups":["Operators","SECURITY.Team"]}""".toByteArray(),
                    "user.name",
                    setOf("operators", "security.team"),
                ),
                BridgeResponseCase(
                    "OK-05 unknown-top-level-fields",
                    MediaType.JSON,
                    """{"user":"user","groups":["group"],"version":2,"metadata":{"sentinel":true}}"""
                        .toByteArray(),
                    "user",
                    setOf("group"),
                ),
            )

        return cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val result = bridgeResult(HttpStatus.OK, case.mediaType, case.body)
                val identity = assertIs<ExternalIdentityLookupResult.Resolved>(result, case.name).identity
                assertEquals(case.expectedUser, identity.user, case.name)
                assertEquals(case.expectedGroups, identity.groups, case.name)
            }
        }
    }

    /** FAIL-01: Every final status from 201 through 599 is one provider-status failure. */
    @TestFactory
    fun `every non 200 final bridge status is provider failure`(): List<DynamicTest> {
        val bridge = fixture.startServer { request ->
            val code = requireNotNull(URI.create(request.path()).query)
                .substringAfter("status=")
                .toInt()
            HttpResponse.of(HttpStatus.valueOf(code))
        }
        return (201..599).map { code ->
            DynamicTest.dynamicTest("FAIL-01 status-$code") {
                val endpoint = URI("${fixture.serverUri(bridge)}/identity?status=$code")
                assertUnavailable(
                    ExternalIdentityFailureCode.PROVIDER_STATUS,
                    newClient(endpoint).lookup("token-sentinel-$code").join(),
                    "status-$code",
                )
            }
        }
    }

    /** FAIL-02: Missing and every non-JSON media type reject otherwise valid JSON. */
    @TestFactory
    fun `missing and non json media types are invalid responses`(): List<DynamicTest> =
        listOf(
            "FAIL-02 missing-media-type" to null,
            "FAIL-02 text-plain" to MediaType.PLAIN_TEXT_UTF_8,
        ).map { (name, mediaType) ->
            DynamicTest.dynamicTest(name) {
                assertUnavailable(
                    ExternalIdentityFailureCode.INVALID_RESPONSE,
                    bridgeResult(
                        HttpStatus.OK,
                        mediaType,
                        """{"user":"user","groups":[]}""".toByteArray(),
                    ),
                    name,
                )
            }
        }

    /** FAIL-03: Invalid UTF-8, malformed JSON, duplicate keys, and non-object roots all reject. */
    @TestFactory
    fun `every invalid json representation is rejected`(): List<DynamicTest> {
        val cases =
            listOf(
                "FAIL-03 invalid-utf8" to byteArrayOf(0xC3.toByte(), 0x28),
                "FAIL-03 malformed-json" to "{not-json".toByteArray(),
                "FAIL-03 duplicate-key" to
                    """{"user":"one","user":"two","groups":[]}""".toByteArray(),
                "FAIL-03 non-object-root" to "[]".toByteArray(),
            )
        return invalidBodyTests(cases)
    }

    /** FAIL-04: Each missing, non-string, blank, or grammar-invalid user shape rejects. */
    @TestFactory
    fun `every invalid user shape is rejected`(): List<DynamicTest> =
        invalidJsonTests(
            listOf(
                "FAIL-04 missing-user" to """{"groups":[]}""",
                "FAIL-04 non-string-user" to """{"user":7,"groups":[]}""",
                "FAIL-04 blank-user" to """{"user":"   ","groups":[]}""",
                "FAIL-04 invalid-user-grammar" to """{"user":"contains space","groups":[]}""",
            ),
        )

    /** FAIL-05: Every invalid groups container or member shape rejects. */
    @TestFactory
    fun `every invalid groups shape is rejected`(): List<DynamicTest> =
        invalidJsonTests(
            listOf(
                "FAIL-05 missing-groups" to """{"user":"user"}""",
                "FAIL-05 non-array-groups" to """{"user":"user","groups":"group"}""",
                "FAIL-05 non-string-group" to """{"user":"user","groups":[7]}""",
                "FAIL-05 blank-group" to """{"user":"user","groups":["   "]}""",
                "FAIL-05 invalid-group-grammar" to """{"user":"user","groups":["contains space"]}""",
            ),
        )

    /** FAIL-06: Groups that collide only after normalization reject the document. */
    @Test
    fun `groups duplicated after normalization are rejected`() {
        assertUnavailable(
            ExternalIdentityFailureCode.INVALID_RESPONSE,
            bridgeResult(
                HttpStatus.OK,
                MediaType.JSON,
                """{"user":"user","groups":["Operators","operators"]}""".toByteArray(),
            ),
            "FAIL-06",
        )
    }

    /** FAIL-07: The 129th distinct normalized group rejects the complete document. */
    @Test
    fun `identity document rejects 129 unique groups`() {
        val groups = (0..128).joinToString(",") { index -> "\"group-$index\"" }

        assertUnavailable(
            ExternalIdentityFailureCode.INVALID_RESPONSE,
            bridgeResult(
                HttpStatus.OK,
                MediaType.JSON,
                """{"user":"user","groups":[$groups]}""".toByteArray(),
            ),
            "FAIL-07",
        )
    }

    /** FAIL-08: The standard Armeria 10 MiB aggregate bound maps to invalid response. */
    @Test
    fun `identity response over standard aggregate limit is invalid`() {
        val oversized = ByteArray(10 * 1024 * 1024 + 1) { 'x'.code.toByte() }

        assertUnavailable(
            ExternalIdentityFailureCode.INVALID_RESPONSE,
            bridgeResult(HttpStatus.OK, MediaType.JSON, oversized),
            "FAIL-08",
        )
    }

    /** FAIL-09: Premature peer close maps to the sole safe transport category. */
    @Test
    fun `premature bridge close is transport failure`() {
        DisconnectingTestUpstream("identity-premature-close").use { bridge ->
            val result =
                newClient(URI("${bridge.uri}/identity"))
                    .lookup("token-sentinel")
                    .join()

            assertUnavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR, result, "FAIL-09")
            kotlin.test.assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) { bridge.acceptedConnections > 0 },
                "Bridge reset fixture did not observe a connection",
            )
        }
    }

    /** FAIL-10: Canonical non-ephemeral unreachable port maps connect refusal to transport. */
    @Test
    fun `deterministic bridge connect failure is transport failure`() {
        val port = GatewayProcessFixture.reserveNonEphemeralPort()

        val result =
            newClient(URI("http://127.0.0.1:$port/identity"))
                .lookup("token-sentinel")
                .join()

        assertUnavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR, result, "FAIL-10")
    }

    /** FAIL-11 headers/body: Whole-exchange timeout aborts each observed pending response phase. */
    @TestFactory
    fun `whole exchange deadline covers response headers and body phases`(): List<DynamicTest> =
        listOf("headers", "body").map { phase ->
            DynamicTest.dynamicTest("FAIL-11 $phase-phase") {
                val phaseReached = CountDownLatch(1)
                val exchangeCancelled = CountDownLatch(1)
                val bridge =
                    fixture.startServer(
                        com.linecorp.armeria.server.HttpService { ctx, _ ->
                            ctx.whenRequestCancelling().thenRun(exchangeCancelled::countDown)
                            when (phase) {
                                "headers" -> {
                                    phaseReached.countDown()
                                    HttpResponse.of(CompletableFuture<HttpResponse>())
                                }

                                else ->
                                    HttpResponse.streaming().also { response ->
                                        response.write(
                                            ResponseHeaders.builder(HttpStatus.OK)
                                                .contentType(MediaType.JSON)
                                                .build(),
                                        )
                                        phaseReached.countDown()
                                    }
                            }
                        },
                    )
                val lookup =
                    newClient(
                        endpoint = URI("${fixture.serverUri(bridge)}/identity"),
                        timeout = Duration.ofSeconds(1),
                    ).lookup("token-sentinel")

                kotlin.test.assertTrue(phaseReached.await(2, TimeUnit.SECONDS), "$phase phase was not reached")
                assertUnavailable(ExternalIdentityFailureCode.TIMEOUT, lookup.join(), "FAIL-11 $phase")
                kotlin.test.assertTrue(
                    exchangeCancelled.await(2, TimeUnit.SECONDS),
                    "$phase timeout did not cancel the Bridge exchange",
                )
            }
        }

    /** FAIL-11 acquisition: Deadline expires while an endpoint/pool target remains unavailable. */
    @Test
    fun `whole exchange deadline covers acquisition phase`() {
        val endpointGroup = DynamicEndpointGroup()
        val factory = ClientFactory.builder().workerGroup(1).build()
        val clientContext = AtomicReference<ClientRequestContext>()
        val webClient =
            WebClient.builder()
                .factory(factory)
                .endpointRemapper { endpointGroup }
                .contextCustomizer(clientContext::set)
                .build()
        try {
            val client =
                instrumentedClient(
                    endpoint = URI("http://bridge.invalid/identity"),
                    timeout = Duration.ofMillis(500),
                    webClient = webClient,
                )

            assertUnavailable(
                ExternalIdentityFailureCode.TIMEOUT,
                client.lookup("acquisition-token-sentinel").join(),
                "FAIL-11 acquisition",
            )
            val context = assertNotNull(clientContext.get(), "client request context was not created")
            assertFalse(
                context.log().isAvailable(RequestLogProperty.SESSION),
                "acquisition test unexpectedly obtained a network session",
            )
            assertExchangeTerminatedByCancellation(context, "FAIL-11 acquisition")
            client.close()
        } finally {
            endpointGroup.close()
            factory.closeAsync().join()
        }
    }

    /** FAIL-11 connect: TCP accept without TLS session completion remains covered by the deadline. */
    @Test
    fun `whole exchange deadline covers connect phase`() {
        HoldingTestEndpoint("identity-connect", observeFirstByte = true).use { endpoint ->
            val factory =
                ClientFactory.builder()
                    .workerGroup(1)
                    .tlsNoVerify()
                    .build()
            val clientContext = AtomicReference<ClientRequestContext>()
            val webClient =
                WebClient.builder()
                    .factory(factory)
                    .contextCustomizer(clientContext::set)
                    .build()
            try {
                val uri = URI("https://127.0.0.1:${endpoint.uri.port}/identity")
                val client = instrumentedClient(uri, Duration.ofMillis(500), webClient)
                val lookup = client.lookup("connect-token-sentinel")

                assertTrue(endpoint.awaitAccepted(Duration.ofSeconds(2)), "TCP connect was not accepted")
                assertEquals(
                    TLS_HANDSHAKE_RECORD_TYPE,
                    endpoint.awaitFirstByte(Duration.ofSeconds(2)),
                    "connect fixture did not observe a TLS handshake record",
                )
                assertUnavailable(ExternalIdentityFailureCode.TIMEOUT, lookup.join(), "FAIL-11 connect")
                assertExchangeTerminatedByCancellation(
                    assertNotNull(clientContext.get(), "client request context was not created"),
                    "FAIL-11 connect",
                )
                endpoint.close()
                client.close()
            } finally {
                factory.closeAsync().join()
            }
        }
    }

    /** FAIL-11 write: A zero-body request with a socket-blocking header is aborted by the deadline. */
    @Test
    fun `whole exchange deadline covers request write phase`() {
        HoldingTestEndpoint("identity-write").use { endpoint ->
            val factory =
                ClientFactory.builder()
                    .workerGroup(1)
                    .preferHttp1(true)
                    .channelOption(ChannelOption.SO_SNDBUF, 1_024)
                    .build()
            val clientContext = AtomicReference<ClientRequestContext>()
            val webClient =
                WebClient.builder()
                    .factory(factory)
                    .contextCustomizer(clientContext::set)
                    .build()
            try {
                val client =
                    instrumentedClient(
                        endpoint = URI("${endpoint.uri}/identity"),
                        timeout = Duration.ofMillis(500),
                        webClient = webClient,
                    )
                val token = "w".repeat(4 * 1024 * 1024)
                val lookup = client.lookup(token)

                assertTrue(endpoint.awaitAccepted(Duration.ofSeconds(2)), "write fixture did not accept TCP")
                assertUnavailable(ExternalIdentityFailureCode.TIMEOUT, lookup.join(), "FAIL-11 write")
                val context = assertNotNull(clientContext.get(), "client request context was not created")
                assertTrue(context.log().isAvailable(RequestLogProperty.SESSION), "write fixture had no session")
                assertFalse(
                    context.log().isAvailable(RequestLogProperty.REQUEST_END_TIME),
                    "request write completed before the held-phase deadline",
                )
                assertExchangeTerminatedByCancellation(context, "FAIL-11 write")
                endpoint.close()
                client.close()
            } finally {
                factory.closeAsync().join()
            }
        }
    }

    /** LIFE-01: N held permits admit no queue and N+1 completes overloaded without a Bridge call. */
    @Test
    fun `lookup admission is an immediate bounded nonfair semaphore`() {
        val bridgeCalls = java.util.concurrent.atomic.AtomicInteger()
        val allPermitsHeld = CountDownLatch(2)
        val bridge =
            fixture.startServer {
                bridgeCalls.incrementAndGet()
                allPermitsHeld.countDown()
                HttpResponse.streaming()
            }
        val client = newClient(URI("${fixture.serverUri(bridge)}/identity"), Duration.ofSeconds(5), 2)
        val first = client.lookup("first-holder-token")
        val second = client.lookup("second-holder-token")
        assertTrue(allPermitsHeld.await(2, TimeUnit.SECONDS), "N holders did not acquire both permits")

        val excess = client.lookup("excess-token")

        assertTrue(excess.isDone, "N+1 must complete without queueing")
        assertUnavailable(ExternalIdentityFailureCode.OVERLOADED, excess.join(), "LIFE-01")
        assertEquals(2, bridgeCalls.get(), "overloaded lookup reached Bridge")
        first.cancel(false)
        second.cancel(false)
        client.close()
    }

    /** LIFE-06: Callers cannot forge completion of the permit-owning lookup result. */
    @Test
    fun `caller completion cannot bypass lookup terminal ownership`() {
        val bridgeReached = CountDownLatch(1)
        val bridge =
            fixture.startServer {
                bridgeReached.countDown()
                HttpResponse.streaming()
            }
        val client =
            newClient(
                URI("${fixture.serverUri(bridge)}/identity"),
                timeout = Duration.ofSeconds(5),
                maxConcurrentLookups = 1,
            )
        val lookup = client.lookup("owner-token-sentinel")
        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "owner lookup did not reach Bridge")

        val forged =
            lookup.complete(
                ExternalIdentityLookupResult.Unavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR),
            )

        assertFalse(forged, "caller forged the permit-owning terminal result")
        assertFalse(lookup.isDone, "forged completion terminated the active lookup")
        assertUnavailable(
            ExternalIdentityFailureCode.OVERLOADED,
            client.lookup("excess-token-sentinel").join(),
            "LIFE-06 forged completion",
        )
        assertTrue(lookup.cancel(false), "owner lookup did not accept cancellation")
        client.close()
    }

    /** LIFE-06: Every remaining CompletableFuture terminal injector is rejected. */
    @TestFactory
    fun `caller terminal injection APIs cannot bypass lookup ownership`(): List<DynamicTest> =
        listOf<(CompletableFuture<ExternalIdentityLookupResult>) -> Unit>(
            { lookup -> assertFalse(lookup.completeExceptionally(IllegalStateException("forged"))) },
            { lookup ->
                assertFailsWith<UnsupportedOperationException> {
                    lookup.obtrudeValue(
                        ExternalIdentityLookupResult.Unavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR),
                    )
                }
            },
            { lookup ->
                assertFailsWith<UnsupportedOperationException> {
                    lookup.obtrudeException(IllegalStateException())
                }
            },
            { lookup -> assertFailsWith<UnsupportedOperationException> { lookup.completeAsync { forgedResult() } } },
            { lookup ->
                assertFailsWith<UnsupportedOperationException> {
                    lookup.completeAsync({ forgedResult() }, Runnable::run)
                }
            },
            { lookup -> assertFailsWith<UnsupportedOperationException> { lookup.orTimeout(0, TimeUnit.NANOSECONDS) } },
            { lookup ->
                assertFailsWith<UnsupportedOperationException> {
                    lookup.completeOnTimeout(forgedResult(), 0, TimeUnit.NANOSECONDS)
                }
            },
        ).mapIndexed { index, inject ->
            DynamicTest.dynamicTest("LIFE-06 terminal-injector-$index") {
                val bridgeReached = CountDownLatch(1)
                val bridge = fixture.startServer {
                    bridgeReached.countDown()
                    HttpResponse.streaming()
                }
                val client = newClient(URI("${fixture.serverUri(bridge)}/identity"), Duration.ofSeconds(5), 1)
                val lookup = client.lookup("injector-$index-token-sentinel")
                assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "injector $index did not reach Bridge")

                inject(lookup)

                assertFalse(lookup.isDone, "injector $index terminated the owner future")
                assertTrue(lookup.cancel(false), "injector $index prevented owned cancellation")
                client.close()
            }
        }

    /** LIFE-02: Success, provider status, invalid response, timeout, and cancellation release one permit. */
    @TestFactory
    fun `every local terminal path releases its permit exactly once`(): List<DynamicTest> =
        listOf("success", "provider-status", "invalid-response", "timeout", "cancellation").map { terminal ->
            DynamicTest.dynamicTest("LIFE-02 $terminal") {
                val bridgeCalls = java.util.concurrent.atomic.AtomicInteger()
                val firstReached = CountDownLatch(1)
                val secondReached = CountDownLatch(1)
                val bridge =
                    fixture.startServer {
                        when (bridgeCalls.incrementAndGet()) {
                            1 -> {
                                firstReached.countDown()
                                when (terminal) {
                                    "success" ->
                                        HttpResponse.of(
                                            HttpStatus.OK,
                                            MediaType.JSON,
                                            """{"user":"user","groups":[]}""",
                                        )
                                    "provider-status" -> HttpResponse.of(HttpStatus.FORBIDDEN)
                                    "invalid-response" -> HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{invalid")
                                    else -> HttpResponse.streaming()
                                }
                            }
                            else -> {
                                secondReached.countDown()
                                HttpResponse.streaming()
                            }
                        }
                    }
                val client =
                    newClient(
                        URI("${fixture.serverUri(bridge)}/identity"),
                        timeout = Duration.ofSeconds(2),
                        maxConcurrentLookups = 1,
                    )
                val first = client.lookup("first-$terminal-token")
                assertTrue(firstReached.await(2, TimeUnit.SECONDS), "$terminal first lookup did not start")
                when (terminal) {
                    "cancellation" -> assertTrue(first.cancel(false), "$terminal did not cancel")
                    else -> first.join()
                }

                val holder = client.lookup("second-$terminal-token")
                assertTrue(secondReached.await(2, TimeUnit.SECONDS), "$terminal did not release its permit")
                assertFalse(holder.isDone, "$terminal follow-up did not remain the sole permit holder")
                val excess = client.lookup("third-$terminal-token")
                assertUnavailable(ExternalIdentityFailureCode.OVERLOADED, excess.join(), terminal)
                assertEquals(2, bridgeCalls.get(), "$terminal over-released its permit")
                holder.cancel(false)
                client.close()
            }
        }

    /** LIFE-02 transport: A typed connection failure releases admission for the next attempt. */
    @Test
    fun `transport terminal path releases its permit`() {
        DisconnectingTestUpstream("identity-permit-transport").use { bridge ->
            val client = newClient(URI("${bridge.uri}/identity"), maxConcurrentLookups = 1)

            repeat(2) { index ->
                assertUnavailable(
                    ExternalIdentityFailureCode.TRANSPORT_ERROR,
                    client.lookup("transport-$index-token").join(),
                    "LIFE-02 transport-$index",
                )
            }

            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) { bridge.acceptedConnections >= 2 },
                "second transport exchange was not admitted",
            )
            client.close()
        }
    }

    /** LIFE-02: Failure while installing the deadline aborts the published credential-bearing exchange. */
    @Test
    fun `deadline scheduling failure aborts the installed exchange`() {
        val bridge = fixture.startServer { HttpResponse.streaming() }
        val clientContext = AtomicReference<ClientRequestContext>()
        val webClient = WebClient.builder().contextCustomizer(clientContext::set).build()
        val rejectedScheduler = ScheduledThreadPoolExecutor(1).apply { shutdownNow() }
        val client =
            instrumentedClient(
                endpoint = URI("${fixture.serverUri(bridge)}/identity"),
                timeout = Duration.ofSeconds(5),
                webClient = webClient,
                maxConcurrentLookups = 1,
                scheduler = rejectedScheduler,
            )

        val lookup = client.lookup("schedule-failure-token-sentinel")

        assertUnavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR, lookup.join(), "deadline scheduling")
        assertExchangeTerminatedByCancellation(clientContext.get(), "deadline scheduling failure")
        assertReleasedAsyncOwnership(lookup, "schedule-failure-token-sentinel")
        client.close()
    }

    /** LIFE-02: Repeated owner shutdown cancels and releases one active permit exactly once. */
    @Test
    fun `shutdown terminal path releases its permit exactly once`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCancelled = java.util.concurrent.atomic.AtomicInteger()
        val bridge =
            fixture.startServer(
                com.linecorp.armeria.server.HttpService { ctx, _ ->
                    bridgeReached.countDown()
                    ctx.whenRequestCancelling().thenRun(bridgeCancelled::incrementAndGet)
                    HttpResponse.streaming()
                },
            )
        val client = newClient(URI("${fixture.serverUri(bridge)}/identity"), Duration.ofSeconds(5), 1)
        val terminalCallbacks = java.util.concurrent.atomic.AtomicInteger()
        val lookup = client.lookup("shutdown-permit-token-sentinel")
        lookup.whenComplete { _, _ -> terminalCallbacks.incrementAndGet() }
        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "shutdown permit holder did not start")
        assertAvailablePermits(client, 0)

        client.close()
        client.close()

        assertTrue(lookup.isCancelled, "shutdown did not cancel the permit holder")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { bridgeCancelled.get() == 1 },
            "shutdown did not cancel Bridge exactly once",
        )
        assertEquals(1, terminalCallbacks.get(), "shutdown published multiple terminal callbacks")
        assertAvailablePermits(client, 1)
    }

    /** LIFE-06: Each success, cancellation, shutdown, or timeout contender can win exactly once. */
    @TestFactory
    @Suppress("LongMethod")
    fun `each terminal race contender publishes one result and downstream effect`(): List<DynamicTest> =
        listOf("success", "cancellation", "shutdown", "timeout").map { winner ->
            DynamicTest.dynamicTest("LIFE-06 $winner-wins") {
                val bridgeReached = CountDownLatch(1)
                val bridgeCancelled = CountDownLatch(1)
                val bridgeRelease = CompletableFuture<HttpResponse>()
                val bridge =
                    fixture.startServer(
                        com.linecorp.armeria.server.HttpService { ctx, _ ->
                            bridgeReached.countDown()
                            ctx.whenRequestCancelling().thenRun(bridgeCancelled::countDown)
                            HttpResponse.of(bridgeRelease)
                        },
                    )
                TestBridgeTelemetry().use { telemetry ->
                    val client =
                        telemetry.newClient(
                            endpoint = URI("${fixture.serverUri(bridge)}/identity"),
                            timeout = if (winner == "timeout") Duration.ofMillis(300) else Duration.ofSeconds(5),
                            maxConcurrentLookups = 1,
                        )
                    val terminalCallbacks = java.util.concurrent.atomic.AtomicInteger()
                    val downstreamEffects = java.util.concurrent.atomic.AtomicInteger()
                    val terminalReached = CountDownLatch(1)
                    val lookup = client.lookup("race-$winner-token-sentinel")
                    lookup.thenAccept { downstreamEffects.incrementAndGet() }
                    lookup.whenComplete { _, _ ->
                        terminalCallbacks.incrementAndGet()
                        terminalReached.countDown()
                    }
                    assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "$winner race did not reach Bridge")

                    when (winner) {
                        "success" ->
                            bridgeRelease.complete(
                                HttpResponse.of(
                                    HttpStatus.OK,
                                    MediaType.JSON,
                                    """{"user":"user","groups":[]}""",
                                ),
                            )
                        "cancellation" -> lookup.cancel(false)
                        "shutdown" -> client.close()
                    }

                    assertTrue(terminalReached.await(2, TimeUnit.SECONDS), "$winner did not publish terminal state")
                    when (winner) {
                        "success" -> assertIs<ExternalIdentityLookupResult.Resolved>(lookup.join())
                        "timeout" ->
                            assertUnavailable(ExternalIdentityFailureCode.TIMEOUT, lookup.join(), winner)
                        else -> assertFailsWith<CancellationException> { lookup.join() }
                    }
                    assertEquals(1, terminalCallbacks.get(), "$winner published multiple callbacks")
                    assertEquals(if (winner in setOf("success", "timeout")) 1 else 0, downstreamEffects.get(), winner)
                    if (winner != "success") {
                        assertTrue(bridgeCancelled.await(2, TimeUnit.SECONDS), "$winner did not abort Bridge")
                    }
                    telemetry.assertSingleObservation(
                        if (winner == "success") "success" else if (winner == "timeout") "timeout" else "cancelled",
                        error = winner == "timeout",
                    )
                    client.close()
                }
            }
        }

    /** LIFE-06: Success, timeout, cancellation, and shutdown contend in one synchronized race. */
    @Test
    @Suppress("LongMethod")
    fun `all terminal contenders race without duplicate publication`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeRelease = CompletableFuture<HttpResponse>()
        val bridge = fixture.startServer {
            bridgeReached.countDown()
            HttpResponse.of(bridgeRelease)
        }
        val webClient = WebClient.of()
        val timeoutScheduler = CapturingTimeoutScheduler()
        val client =
            instrumentedClient(
                endpoint = URI("${fixture.serverUri(bridge)}/identity"),
                timeout = Duration.ofSeconds(5),
                webClient = webClient,
                maxConcurrentLookups = 1,
                scheduler = timeoutScheduler,
            )
        val lookup = client.lookup("combined-race-token-sentinel")
        val terminalCallbacks = java.util.concurrent.atomic.AtomicInteger()
        val downstreamEffects = java.util.concurrent.atomic.AtomicInteger()
        val competitorsDone = CountDownLatch(4)
        val barrier = CyclicBarrier(5)
        val competitorFailures = CopyOnWriteArrayList<Throwable>()
        lookup.whenComplete { _, _ -> terminalCallbacks.incrementAndGet() }
        lookup.thenAccept { downstreamEffects.incrementAndGet() }
        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "combined race did not reach Bridge")
        val competitors = Executors.newFixedThreadPool(4)
        try {
            listOf(
                {
                    bridgeRelease.complete(
                        HttpResponse.of(
                            HttpStatus.OK,
                            MediaType.JSON,
                            """{"user":"user","groups":[]}""",
                        ),
                    )
                },
                { lookup.cancel(false) },
                { client.close() },
                { timeoutScheduler.capturedCommand().run() },
            ).forEach { contender ->
                competitors.execute(
                    {
                        try {
                            barrier.await(2, TimeUnit.SECONDS)
                            contender()
                        } catch (failure: Throwable) {
                            competitorFailures += failure
                        } finally {
                            competitorsDone.countDown()
                        }
                    },
                )
            }

            barrier.await(2, TimeUnit.SECONDS)
            assertTrue(competitorsDone.await(2, TimeUnit.SECONDS), "terminal contenders did not all run")
            assertTrue(competitorFailures.isEmpty(), "terminal contender failed: $competitorFailures")
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(2), lookup::isDone), "race had no terminal winner")
            assertEquals(1, terminalCallbacks.get(), "race published multiple terminal callbacks")
            assertTrue(downstreamEffects.get() in 0..1, "race duplicated its downstream side effect")
            assertReleasedAsyncOwnership(lookup, "combined-race-token-sentinel")
        } finally {
            competitors.shutdownNow()
            timeoutScheduler.shutdownNow()
            client.close()
        }
    }

    /** OBS-01 and OBS-03: Every finite terminal outcome emits one safe metric pair and CLIENT span. */
    @TestFactory
    fun `every external outcome has exactly one metric pair and span`(): List<DynamicTest> =
        TELEMETRY_OUTCOMES.map { outcome ->
            DynamicTest.dynamicTest("OBS-01 ${outcome.name}") {
                TestBridgeTelemetry().use { telemetry ->
                    val execution = executeTelemetryOutcome(outcome, telemetry)
                    val result = execution.result
                    if (outcome.failureCode != null) {
                        assertUnavailable(outcome.failureCode, assertNotNull(result), outcome.name)
                    }

                    telemetry.assertSingleObservation(outcome.name, outcome.error)
                    execution.client.close()
                }
            }
        }

    /** OBS-03: Every failure span is ERROR while success and cancellation remain non-error. */
    @TestFactory
    fun `each terminal outcome has the exact span status`(): List<DynamicTest> =
        TELEMETRY_OUTCOMES.map { outcome ->
            DynamicTest.dynamicTest("OBS-03 ${outcome.name}") {
                TestBridgeTelemetry().use { telemetry ->
                    val execution = executeTelemetryOutcome(outcome, telemetry)

                    assertEquals(
                        if (outcome.error) StatusCode.ERROR else StatusCode.UNSET,
                        telemetry.spans.single().status.statusCode,
                        outcome.name,
                    )
                    execution.client.close()
                }
            }
        }

    /** OBS-02: Received final headers contribute only their bounded status class. */
    @TestFactory
    fun `telemetry reports only received bridge status class`(): List<DynamicTest> =
        listOf(
            HttpStatus.OK to "2xx",
            HttpStatus.FOUND to "3xx",
            HttpStatus.NOT_FOUND to "4xx",
            HttpStatus.SERVICE_UNAVAILABLE to "5xx",
        ).map { (status, expectedClass) ->
            DynamicTest.dynamicTest("OBS-02 $expectedClass") {
                TestBridgeTelemetry().use { telemetry ->
                    val bridge = fixture.startServer {
                        if (status == HttpStatus.OK) {
                            HttpResponse.of(status, MediaType.JSON, """{"user":"user","groups":[]}""")
                        } else {
                            HttpResponse.of(status)
                        }
                    }
                    telemetry.newClient(URI("${fixture.serverUri(bridge)}/identity"))
                        .lookup("status-token-sentinel")
                        .join()

                    telemetry.assertStatusClass(expectedClass)
                }
            }
        }

    /** OBS-04/LIFE-07: Every terminal family excludes source sentinels from result and telemetry. */
    @TestFactory
    @Suppress("LongMethod")
    fun `every external terminal family retains no credential or provider data`(): List<DynamicTest> =
        TELEMETRY_OUTCOMES.map { outcome ->
            DynamicTest.dynamicTest("OBS-04 ${outcome.name}") {
                val loggerName = "io.vigilant"
                val applicationEvents = fixture.attachAppenderTo(loggerName)
                try {
                    TestBridgeTelemetry().use { telemetry ->
                    val endpointSentinel = "endpoint-sentinel-${outcome.name}"
                    val tokenSentinel = "token-sentinel-${outcome.name}"
                    val userSentinel = "user-sentinel-${outcome.name}"
                    val groupSentinel = "group-sentinel-${outcome.name}"
                    val bodySentinel = "body-sentinel-${outcome.name}"
                    val heldResponseReached = CountDownLatch(1)
                    val bridge =
                        fixture.startServer {
                            when (outcome.name) {
                                "success" ->
                                    HttpResponse.of(
                                        HttpStatus.OK,
                                        MediaType.JSON,
                                        """{"user":"$userSentinel","groups":["$groupSentinel"],""" +
                                            """"payload":"$bodySentinel"}""",
                                    )
                                "provider_status" ->
                                    HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.JSON, bodySentinel)
                                "invalid_response" ->
                                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{$bodySentinel")
                                else ->
                                    HttpResponse.streaming().also { response ->
                                        response.write(
                                            ResponseHeaders.builder(HttpStatus.OK)
                                                .contentType(MediaType.JSON)
                                                .build(),
                                        )
                                        response.write(HttpData.ofUtf8(bodySentinel))
                                        heldResponseReached.countDown()
                                    }
                            }
                        }
                    val endpoint =
                        if (outcome.name == "transport_error") {
                            URI(
                                "http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}/$endpointSentinel",
                            )
                        } else {
                            URI("${fixture.serverUri(bridge)}/$endpointSentinel")
                        }
                    val client = telemetry.newClient(endpoint, Duration.ofMillis(500), 1)
                    val result =
                        when (outcome.name) {
                            "overloaded" -> {
                                val holder = client.lookup("holder-$tokenSentinel")
                                assertTrue(
                                    heldResponseReached.await(2, TimeUnit.SECONDS),
                                    "privacy permit holder did not start",
                                )
                                client.lookup(tokenSentinel).join().also { holder.cancel(false) }
                            }
                            "cancelled" -> {
                                val cancelled = client.lookup(tokenSentinel)
                                assertTrue(
                                    heldResponseReached.await(2, TimeUnit.SECONDS),
                                    "privacy cancellation lookup did not start",
                                )
                                assertTrue(cancelled.cancel(false))
                                null
                            }
                            else -> client.lookup(tokenSentinel).join()
                        }

                    val observations = telemetry.serializedObservations() + applicationEvents.joinToString { event ->
                        "${event.formattedMessage} ${event.argumentArray.contentDeepToString()} " +
                            "${event.throwableProxy?.className} ${event.throwableProxy?.message}"
                    }
                    listOf(
                        endpointSentinel,
                        tokenSentinel,
                        "Authorization",
                        userSentinel,
                        groupSentinel,
                        bodySentinel,
                    ).forEach { forbidden -> assertFalse(observations.contains(forbidden), forbidden) }
                    listOf(endpointSentinel, tokenSentinel, "Authorization", bodySentinel).forEach { forbidden ->
                        assertFalse(result.toString().contains(forbidden), "result retained $forbidden")
                    }
                    assertTrue(telemetry.spans.all { it.events.isEmpty() }, "recordException/events are forbidden")
                    client.close()
                    }
                } finally {
                    fixture.detachAppenderFrom(loggerName)
                }
            }
        }

    /** LIFE-07: Every finite terminal family clears exchange and queued-task ownership. */
    @TestFactory
    fun `every terminal family releases all asynchronous ownership`(): List<DynamicTest> =
        TELEMETRY_OUTCOMES.map { outcome ->
            DynamicTest.dynamicTest("LIFE-07 ${outcome.name}") {
                TestBridgeTelemetry().use { telemetry ->
                    val execution = executeTelemetryOutcome(outcome, telemetry)
                    val token =
                        when (outcome.name) {
                            "overloaded" -> "overload-token-sentinel"
                            "cancelled" -> "cancel-token-sentinel"
                            else -> "${outcome.name}-token-sentinel"
                        }

                    assertReleasedAsyncOwnership(execution.lookup, token)
                    execution.client.close()
                }
            }
        }

    /** Executes one finite telemetry outcome while retaining its client for post-assertion cleanup. */
    private fun executeTelemetryOutcome(
        outcome: TelemetryOutcome,
        telemetry: TestBridgeTelemetry,
    ): TelemetryExecution {
        val heldResponseReached = CountDownLatch(1)
        val bridge =
            fixture.startServer {
                when (outcome.name) {
                    "success" ->
                        HttpResponse.of(
                            HttpStatus.OK,
                            MediaType.JSON,
                            """{"user":"user","groups":[]}""",
                        )
                    "provider_status" -> HttpResponse.of(HttpStatus.FOUND)
                    "invalid_response" -> HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{invalid")
                    else ->
                        HttpResponse.streaming().also { response ->
                            heldResponseReached.countDown()
                            if (outcome.name == "timeout") {
                                response.write(ResponseHeaders.of(HttpStatus.OK))
                            }
                        }
                }
            }
        val endpoint =
            if (outcome.name == "transport_error") {
                URI("http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}/identity")
            } else {
                URI("${fixture.serverUri(bridge)}/identity")
            }
        val client = telemetry.newClient(endpoint, Duration.ofMillis(500), 1)
        val execution =
            when (outcome.name) {
                "overloaded" -> {
                    val holder = client.lookup("holder-token-sentinel")
                    assertTrue(heldResponseReached.await(2, TimeUnit.SECONDS), "permit holder did not start")
                    val lookup = client.lookup("overload-token-sentinel")
                    TelemetryExecution(client, lookup, lookup.join().also { assertFalse(holder.isDone) })
                }
                "cancelled" -> {
                    val lookup = client.lookup("cancel-token-sentinel")
                    assertTrue(heldResponseReached.await(2, TimeUnit.SECONDS), "cancelled lookup did not start")
                    assertTrue(lookup.cancel(false))
                    TelemetryExecution(client, lookup, null)
                }
                else -> {
                    val lookup = client.lookup("${outcome.name}-token-sentinel")
                    TelemetryExecution(client, lookup, lookup.join())
                }
            }
        return execution
    }

    /** Creates dynamic invalid-response cases from exact raw response bytes. */
    private fun invalidBodyTests(cases: List<Pair<String, ByteArray>>): List<DynamicTest> =
        cases.map { (name, body) ->
            DynamicTest.dynamicTest(name) {
                assertUnavailable(
                    ExternalIdentityFailureCode.INVALID_RESPONSE,
                    bridgeResult(HttpStatus.OK, MediaType.JSON, body),
                    name,
                )
            }
        }

    /** Creates dynamic invalid-response cases from UTF-8 JSON fixtures. */
    private fun invalidJsonTests(cases: List<Pair<String, String>>): List<DynamicTest> =
        invalidBodyTests(cases.map { (name, body) -> name to body.toByteArray() })

    /** Executes one lookup against a real Bridge returning the exact supplied response. */
    private fun bridgeResult(
        status: HttpStatus,
        mediaType: MediaType?,
        body: ByteArray,
    ): ExternalIdentityLookupResult {
        val bridge =
            fixture.startServer {
                val headers =
                    ResponseHeaders.builder(status)
                        .apply { mediaType?.let(::contentType) }
                        .build()
                HttpResponse.of(headers, HttpData.wrap(body))
            }
        return newClient(URI("${fixture.serverUri(bridge)}/identity"))
            .lookup("token-sentinel")
            .join()
    }

    /** Builds one Bridge client with shared no-op telemetry and exact production defaults. */
    private fun newClient(
        endpoint: URI,
        timeout: Duration = Duration.ofSeconds(1),
        maxConcurrentLookups: Int = 2,
    ): BridgeIdentityClient =
        instrumentedClient(endpoint, timeout, WebClient.of(), maxConcurrentLookups)

    /** Builds one Bridge client around a phase-controllable real Armeria client. */
    private fun instrumentedClient(
        endpoint: URI,
        timeout: Duration,
        webClient: WebClient,
        maxConcurrentLookups: Int = 2,
        scheduler: ScheduledExecutorService = sharedTimeoutScheduler(webClient),
    ): BridgeIdentityClient {
        val telemetry = OpenTelemetry.noop()
        return BridgeIdentityClient(
            settings = ExternalIdentitySettings(endpoint, timeout),
            webClient = webClient,
            timeoutScheduler = scheduler,
            maxConcurrentLookups = maxConcurrentLookups,
            meter = telemetry.getMeter("bridge-test"),
            tracer = telemetry.getTracer("bridge-test"),
        )
    }

    /** Asserts one safe finite unavailable result without consulting production mapping logic. */
    private fun assertUnavailable(
        expected: ExternalIdentityFailureCode,
        result: ExternalIdentityLookupResult,
        caseName: String,
    ) {
        assertEquals(expected, assertIs<ExternalIdentityLookupResult.Unavailable>(result, caseName).code, caseName)
    }

    /** Proves Armeria published response cancellation for the owned exchange. */
    private fun assertExchangeTerminatedByCancellation(
        context: ClientRequestContext,
        caseName: String,
    ) {
        val cause = context.whenResponseCancelled().get(2, TimeUnit.SECONDS)
        assertNotNull(cause, "$caseName completed without a cancellation cause")
    }

    /** Asserts the exact internal permit count required by once-only lifecycle evidence. */
    private fun assertAvailablePermits(client: BridgeIdentityClient, expected: Int) {
        val permitsField = BridgeIdentityClient::class.java.getDeclaredField("permits")
        permitsField.trySetAccessible()
        val permits = assertIs<java.util.concurrent.Semaphore>(permitsField.get(client))
        assertEquals(expected, permits.availablePermits(), "unexpected Bridge admission permit count")
    }

    /** LIFE-07: Terminal operations retain neither exchange handles nor their raw credential. */
    private fun assertReleasedAsyncOwnership(
        lookup: CompletableFuture<ExternalIdentityLookupResult>,
        token: String,
    ) {
        val retained =
            if (lookup.javaClass.name.startsWith("io.vigilant.")) {
                lookup.javaClass.declaredFields.mapNotNull { field ->
                    check(field.trySetAccessible()) { "cannot inspect owned lookup field ${field.name}" }
                    (field.get(lookup) as? AtomicReference<*>)?.get()
                }
            } else {
                emptyList()
            }
        assertTrue(retained.isEmpty(), "terminal lookup retained an exchange or queued-task handle")
        assertFalse(lookup.toString().contains(token), "terminal lookup retained the raw token")
    }

    /** Returns a safe value used only to attempt forbidden caller-owned completion. */
    private fun forgedResult(): ExternalIdentityLookupResult =
        ExternalIdentityLookupResult.Unavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR)

    /** One valid Bridge response and its independently specified normalized identity. */
    private data class BridgeResponseCase(
        /** Stable acceptance-case name retained in the dynamic test report. */
        val name: String,
        /** Exact Bridge media type. */
        val mediaType: MediaType,
        /** Exact Bridge response bytes. */
        val body: ByteArray,
        /** Independent expected normalized user. */
        val expectedUser: String,
        /** Independent expected normalized group set. */
        val expectedGroups: Set<String>,
    )

    /** Independent Bridge-side observation of the complete outbound request. */
    private data class ObservedBridgeRequest(
        /** Received request method. */
        val method: HttpMethod,
        /** Received raw path and query. */
        val path: String,
        /** Received request headers. */
        val headers: RequestHeaders,
        /** Received aggregate body length. */
        val bodyLength: Int,
    )

    /** One expected finite telemetry outcome and its terminal status semantics. */
    private data class TelemetryOutcome(
        /** Exact finite outcome label. */
        val name: String,
        /** Safe internal failure result, absent for success and cancellation. */
        val failureCode: ExternalIdentityFailureCode?,
        /** Whether the CLIENT span must finish in error. */
        val error: Boolean,
    )

    /** Completed telemetry scenario plus the client that owns any intentionally held exchange. */
    private data class TelemetryExecution(
        /** Client closed only after terminal observations have been asserted. */
        val client: BridgeIdentityClient,
        /** Terminal lookup whose application-owned handles must already be released. */
        val lookup: CompletableFuture<ExternalIdentityLookupResult>,
        /** Safe lookup result, absent only for cancellation. */
        val result: ExternalIdentityLookupResult?,
    )

    /** Captures the production timeout command so a test can release it through a shared race barrier. */
    private class CapturingTimeoutScheduler : ScheduledThreadPoolExecutor(1) {
        private val captured = CompletableFuture<Runnable>()

        /** Captures one command while retaining a cancellable scheduled handle for production ownership. */
        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            captured.complete(command)
            return super.schedule(command, 1, TimeUnit.DAYS)
        }

        /** Returns the exact command installed by the Bridge lookup. */
        fun capturedCommand(): Runnable = captured.get(2, TimeUnit.SECONDS)
    }

    /** Owns one isolated in-memory meter/tracer pair for exact observation assertions. */
    private class TestBridgeTelemetry : AutoCloseable {
        private val metricReader = TestMetricReader()
        private val meterProvider =
            SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build()
        val spans = CopyOnWriteArrayList<SpanData>()
        private val spanExporter =
            object : SpanExporter {
                /** Retains immutable completed span data for assertions. */
                override fun export(exported: Collection<SpanData>): CompletableResultCode {
                    spans.addAll(exported)
                    return CompletableResultCode.ofSuccess()
                }

                /** No buffered exporter state exists. */
                override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

                /** No external exporter resource exists. */
                override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
            }
        private val tracerProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(spanExporter).build())
                .build()

        /** Builds a Bridge client instrumented only by this isolated SDK pair. */
        fun newClient(
            endpoint: URI,
            timeout: Duration = Duration.ofSeconds(1),
            maxConcurrentLookups: Int = 2,
            webClient: WebClient = WebClient.of(),
        ): BridgeIdentityClient =
            BridgeIdentityClient(
                settings = ExternalIdentitySettings(endpoint, timeout),
                webClient = webClient,
                timeoutScheduler = sharedTimeoutScheduler(webClient),
                maxConcurrentLookups = maxConcurrentLookups,
                meter = meterProvider.get("bridge-telemetry-test"),
                tracer = tracerProvider.get("bridge-telemetry-test"),
            )

        /** Asserts the exact once-only counter, duration, span, and error contract. */
        fun assertSingleObservation(expectedOutcome: String, error: Boolean) {
            val metrics = metricReader.collectAllMetrics()
            val outcomeKey = AttributeKey.stringKey("identity.outcome")
            val modeKey = AttributeKey.stringKey("identity.mode")
            val counter = metrics.single { it.name == "vigilant.identity.external.lookups" }
                .longSumData.points.single()
            assertEquals(1L, counter.value)
            assertEquals("EXTERNAL", counter.attributes.get(modeKey))
            assertEquals(expectedOutcome, counter.attributes.get(outcomeKey))
            val duration = metrics.single { it.name == "vigilant.identity.external.lookup.duration" }
            assertEquals("s", duration.unit)
            val durationPoint = duration.histogramData.points.single()
            assertEquals(1L, durationPoint.count)
            assertTrue(durationPoint.sum >= 0.0)
            assertEquals("EXTERNAL", durationPoint.attributes.get(modeKey))
            assertEquals(expectedOutcome, durationPoint.attributes.get(outcomeKey))
            val span = spans.single()
            assertEquals("vigilant.identity.external.lookup", span.name)
            assertEquals(SpanKind.CLIENT, span.kind)
            assertEquals("EXTERNAL", span.attributes.get(modeKey))
            assertEquals(expectedOutcome, span.attributes.get(outcomeKey))
            assertEquals(if (error) StatusCode.ERROR else StatusCode.UNSET, span.status.statusCode)
            if (expectedOutcome in setOf("transport_error", "overloaded", "cancelled")) {
                val statusKey = AttributeKey.stringKey("http.response.status_class")
                assertEquals(null, counter.attributes.get(statusKey))
                assertEquals(null, durationPoint.attributes.get(statusKey))
                assertEquals(null, span.attributes.get(statusKey))
            }
        }

        /** Asserts that span and metric points carry one exact bounded status class. */
        fun assertStatusClass(expectedClass: String) {
            val statusKey = AttributeKey.stringKey("http.response.status_class")
            val metrics = metricReader.collectAllMetrics()
                .filter { it.name.startsWith("vigilant.identity.external.") }
            metrics.forEach { metric ->
                assertEquals(expectedClass, metric.data.points.single().attributes.get(statusKey))
            }
            assertEquals(expectedClass, spans.single().attributes.get(statusKey))
        }

        /** Serializes only collected metric and span data for forbidden-sentinel scanning. */
        fun serializedObservations(): String =
            metricReader.collectAllMetrics().joinToString() + spans.joinToString()

        /** Closes both isolated OpenTelemetry providers. */
        override fun close() {
            tracerProvider.close()
            meterProvider.close()
        }
    }

    private companion object {
        const val TLS_HANDSHAKE_RECORD_TYPE = 0x16
        val TELEMETRY_OUTCOMES =
            listOf(
                TelemetryOutcome("success", null, false),
                TelemetryOutcome("provider_status", ExternalIdentityFailureCode.PROVIDER_STATUS, true),
                TelemetryOutcome("invalid_response", ExternalIdentityFailureCode.INVALID_RESPONSE, true),
                TelemetryOutcome("timeout", ExternalIdentityFailureCode.TIMEOUT, true),
                TelemetryOutcome("transport_error", ExternalIdentityFailureCode.TRANSPORT_ERROR, true),
                TelemetryOutcome("overloaded", ExternalIdentityFailureCode.OVERLOADED, true),
                TelemetryOutcome("cancelled", null, false),
            )
    }
}

/** Resolves the event loop owned by the same factory as one test WebClient. */
private fun sharedTimeoutScheduler(webClient: WebClient) =
    webClient.options().factory().eventLoopGroup().next()
