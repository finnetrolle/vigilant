package io.vigilant.gateway

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.HttpService
import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.config.ExternalIdentitySettings
import io.vigilant.gateway.config.RuntimeEnvironment
import io.vigilant.gateway.config.loadAppConfig
import io.vigilant.gateway.identity.DummyIdentityExtractor
import io.vigilant.gateway.identity.ExternalIdentityExtractor
import io.vigilant.gateway.identity.ExternalIdentityLookup
import io.vigilant.gateway.identity.ExternalIdentityLookupResult
import io.vigilant.gateway.identity.OfflineJwtIdentityExtractor
import io.vigilant.gateway.identity.jwtIdentitySettings
import io.vigilant.gateway.identity.jwtTestKey
import io.vigilant.gateway.proxy.OutboundClientResources
import io.opentelemetry.api.OpenTelemetry
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Focused startup selection evidence for the common Bearer identity contract. */
class AppComponentIdentityTest {
    private val fixture = GatewayTestFixture()

    /** Stops every real Bridge server after its ownership scenario. */
    @AfterTest
    fun closeFixture() {
        fixture.close()
    }

    /** Each validated settings variant selects only its matching extractor implementation. */
    @TestFactory
    fun `startup selects exact configured identity implementation`(): List<DynamicTest> {
        val lookup = ExternalIdentityLookup {
            CompletableFuture.completedFuture(
                ExternalIdentityLookupResult.Unavailable(
                    io.vigilant.gateway.identity.ExternalIdentityFailureCode.TRANSPORT_ERROR,
                ),
            )
        }
        return listOf(
            DynamicTest.dynamicTest("CFG-15 DUMMY selection") {
                assertIs<DummyIdentityExtractor>(
                    AppComponent.identityExtractorBinding(DummyIdentitySettings("test-user", emptySet())) {
                        error("Dummy selection must not resolve External resources")
                    },
                )
            },
            DynamicTest.dynamicTest("CFG-15 JWT selection") {
                assertIs<OfflineJwtIdentityExtractor>(
                    AppComponent.identityExtractorBinding(jwtIdentitySettings(jwtTestKey("key-startup"))) {
                        error("JWT selection must not resolve External resources")
                    },
                )
            },
            DynamicTest.dynamicTest("CFG-01..03 EXTERNAL selection") {
                assertIs<ExternalIdentityExtractor>(
                    AppComponent.identityExtractorBinding(
                        ExternalIdentitySettings(URI("http://127.0.0.1/identity"), Duration.ofSeconds(1)),
                    ) { lookup },
                )
            },
        )
    }

    /** CFG-15: Dummy and JWT resource owners do not construct a Bridge client or semaphore. */
    @Test
    fun `dummy and jwt outbound resources contain no external client`() {
        val base =
            loadAppConfig(
                env =
                    mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_ENVIRONMENT" to "test",
                        "VIGILANT_IDENTITY_MODE" to "DUMMY",
                        "VIGILANT_IDENTITY_DUMMY_USER" to "test-user",
                    ),
                defaultConfigPaths = emptyList(),
            )
        val telemetry = OpenTelemetry.noop()
        listOf(
            base,
            base.copy(
                environment = RuntimeEnvironment.PRODUCTION,
                identity = jwtIdentitySettings(jwtTestKey("key-no-bridge")),
            ),
        ).forEach { config ->
            OutboundClientResources(
                config,
                telemetry.getMeter("no-bridge-owner-test"),
                telemetry.getTracer("no-bridge-owner-test"),
            ).use { resources -> assertNull(resources.externalIdentityLookup) }
        }
    }

    /** CFG-16: External owner construction succeeds without contacting an unavailable Bridge. */
    @Test
    fun `external outbound resources perform no startup health check`() {
        val config =
            loadAppConfig(
                env =
                    mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_ENVIRONMENT" to "production",
                        "VIGILANT_IDENTITY_MODE" to "EXTERNAL",
                        "VIGILANT_IDENTITY_EXTERNAL_URL" to "http://127.0.0.1:49151/identity",
                    ),
                defaultConfigPaths = emptyList(),
            )
        val telemetry = OpenTelemetry.noop()
        OutboundClientResources(
            config,
            telemetry.getMeter("external-owner-test"),
            telemetry.getTracer("external-owner-test"),
        ).use { resources -> assertNotNull(resources.externalIdentityLookup) }
    }

    /** The application owner cancels an active Bridge exchange and remains once-only on repeated close. */
    @Test
    fun `outbound resources close bridge work before the shared factory`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCancelled = CountDownLatch(1)
        val bridge =
            fixture.startServer(
                HttpService { ctx, _ ->
                    bridgeReached.countDown()
                    ctx.whenRequestCancelling().thenRun(bridgeCancelled::countDown)
                    HttpResponse.streaming()
                },
            )
        val config =
            loadAppConfig(
                env =
                    mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_ENVIRONMENT" to "production",
                        "VIGILANT_IDENTITY_MODE" to "EXTERNAL",
                        "VIGILANT_IDENTITY_EXTERNAL_URL" to "${fixture.serverUri(bridge)}/identity",
                        "VIGILANT_IDENTITY_EXTERNAL_TIMEOUT" to "5s",
                    ),
                defaultConfigPaths = emptyList(),
            )
        val telemetry = OpenTelemetry.noop()
        val resources =
            OutboundClientResources(
                config,
                telemetry.getMeter("external-owner-close-test"),
                telemetry.getTracer("external-owner-close-test"),
            )
        val factory = resources.upstreamWebClient.options().factory()
        val factoryClosed = AtomicInteger()
        factory.whenClosed().thenRun(factoryClosed::incrementAndGet)
        val lookup = requireNotNull(resources.externalIdentityLookup).lookup("owner-close-token-sentinel")
        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "owned Bridge exchange did not start")

        resources.close()
        resources.close()

        assertTrue(lookup.isCancelled, "owner close did not cancel lookup")
        assertTrue(bridgeCancelled.await(2, TimeUnit.SECONDS), "owner close did not abort Bridge exchange")
        assertTrue(factory.isClosed, "owner close did not close the shared factory")
        assertEquals(1, factoryClosed.get(), "repeated owner close published factory closure more than once")
    }
}
