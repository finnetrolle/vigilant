package io.vigilant.gateway.proxy

import io.vigilant.source.BoundedRequestSourceOwner
import io.vigilant.source.RequestSourceIngestResult
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceQuota
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Opens and completes one retained request source from the supplied exact bytes. */
internal fun completeOwner(
    quota: RequestSourceQuota,
    bytes: ByteArray,
): BoundedRequestSourceOwner {
    val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(bytes.size.toLong())).owner
    val result =
        owner.ingest(
            Flow.Publisher { subscriber ->
                subscriber.onSubscribe(
                    object : Flow.Subscription {
                        private var published = false

                        /** Publishes the complete retained body on first positive demand. */
                        override fun request(elements: Long) {
                            if (!published && elements > 0) {
                                published = true
                                subscriber.onNext(ByteBuffer.wrap(bytes))
                                subscriber.onComplete()
                            }
                        }

                        /** This synchronous fixture has no outstanding work to cancel. */
                        override fun cancel() = Unit
                    },
                )
            },
        ).get(5, TimeUnit.SECONDS)
    assertEquals(RequestSourceIngestResult.Complete, result)
    return owner
}
