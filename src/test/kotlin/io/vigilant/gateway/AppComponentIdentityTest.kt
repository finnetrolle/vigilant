package io.vigilant.gateway

import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.identity.DummyIdentityExtractor
import io.vigilant.gateway.identity.OfflineJwtIdentityExtractor
import io.vigilant.gateway.identity.jwtIdentitySettings
import io.vigilant.gateway.identity.jwtTestKey
import kotlin.test.Test
import kotlin.test.assertIs

/** Focused startup selection evidence for the common Bearer identity contract. */
class AppComponentIdentityTest {
    /** Each validated settings variant selects only its matching extractor implementation. */
    @Test
    fun `startup selects exact configured identity implementation`() {
        assertIs<DummyIdentityExtractor>(
            AppComponent.identityExtractorBinding(DummyIdentitySettings("test-user", emptySet())),
        )
        assertIs<OfflineJwtIdentityExtractor>(
            AppComponent.identityExtractorBinding(jwtIdentitySettings(jwtTestKey("key-startup"))),
        )
    }
}
