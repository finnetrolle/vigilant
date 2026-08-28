package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.ByteBufAccessMode
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import org.reactivestreams.FlowAdapters
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Exposes request data objects as Java Flow byte buffers with transport demand unchanged. */
internal fun requestBodyFlowPublisher(request: HttpRequest): Flow.Publisher<ByteBuffer> =
    FlowAdapters.toFlowPublisher(
        Publisher { downstream ->
            request.subscribe(requestDataSubscriber(downstream))
        },
    )

/**
 * Rebuilds a request whose body is demand-driven exact source replay and whose
 * configured consumed identity headers are removed before upstream forwarding.
 *
 * @param original inbound request providing the end-to-end headers to preserve.
 * @param body quota-owned exact replay publisher.
 * @param headersToStrip canonical identity header names consumed by Vigilant.
 */
internal fun replayRequest(
    original: HttpRequest,
    body: Flow.Publisher<ByteBuffer>,
    headersToStrip: Set<String>,
): HttpRequest {
    val dataPublisher: Publisher<HttpData> =
        Publisher { downstream ->
            FlowAdapters.toPublisher(body).subscribe(replayBufferSubscriber(downstream))
        }
    val replayHeaders = original.headers().toBuilder().also { builder ->
        headersToStrip.forEach(builder::remove)
    }.build()
    return HttpRequest.of(replayHeaders, dataPublisher)
}

/** Maps Armeria body objects to read-only buffers copied synchronously by the source. */
private fun requestDataSubscriber(downstream: Subscriber<in ByteBuffer>): Subscriber<HttpObject> {
    lateinit var upstream: Subscription
    return CallbackSubscriber(
        onSubscribeCallback = { subscription ->
            upstream = subscription
            downstream.onSubscribe(
                object : Subscription {
                    /** Requests the same bounded number of body objects from Armeria. */
                    override fun request(elements: Long) = upstream.request(elements)

                    /** Propagates downstream cancellation to the inbound request. */
                    override fun cancel() = upstream.cancel()
                },
            )
        },
        onNextCallback = { item ->
            if (item is HttpData) {
                downstream.onNext(item.byteBuf(ByteBufAccessMode.DUPLICATE).nioBuffer().asReadOnlyBuffer())
            } else {
                upstream.request(1)
            }
        },
        onErrorCallback = downstream::onError,
        onCompleteCallback = downstream::onComplete,
    )
}

/** Maps replay buffers to independently owned Armeria data objects. */
private fun replayBufferSubscriber(downstream: Subscriber<in HttpData>): Subscriber<ByteBuffer> =
    CallbackSubscriber(
        onSubscribeCallback = downstream::onSubscribe,
        onNextCallback = { buffer ->
            val source = buffer.asReadOnlyBuffer()
            val bytes = ByteArray(source.remaining())
            source[bytes]
            downstream.onNext(HttpData.wrap(bytes))
        },
        onErrorCallback = downstream::onError,
        onCompleteCallback = downstream::onComplete,
    )

/** Implements one reactive-streams subscriber from four type-safe callbacks. */
private class CallbackSubscriber<T>(
    private val onSubscribeCallback: (Subscription) -> Unit,
    private val onNextCallback: (T) -> Unit,
    private val onErrorCallback: (Throwable) -> Unit,
    private val onCompleteCallback: () -> Unit,
) : Subscriber<T> {
    /** Publishes the upstream subscription to the configured callback. */
    override fun onSubscribe(subscription: Subscription) = onSubscribeCallback(subscription)

    /** Publishes one stream item to the configured callback. */
    override fun onNext(item: T) = onNextCallback(item)

    /** Publishes a terminal failure to the configured callback. */
    override fun onError(failure: Throwable) = onErrorCallback(failure)

    /** Publishes successful completion to the configured callback. */
    override fun onComplete() = onCompleteCallback()
}
