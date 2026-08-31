package io.vigilant.gateway

import com.linecorp.armeria.common.HttpObject
import java.util.concurrent.atomic.AtomicBoolean
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Relays an HTTP body while recording the first positive downstream demand. */
internal class DemandObservingPublisher(
    private val delegate: Publisher<HttpObject>,
    private val demandObserved: AtomicBoolean,
) : Publisher<HttpObject> {
    /** Relays the original body while wrapping only its downstream subscription. */
    override fun subscribe(subscriber: Subscriber<in HttpObject>) {
        delegate.subscribe(
            object : Subscriber<HttpObject> {
                /** Exposes a subscription that records positive demand before forwarding it. */
                override fun onSubscribe(subscription: Subscription) {
                    subscriber.onSubscribe(
                        object : Subscription {
                            /** Records positive demand before forwarding it to the server request. */
                            override fun request(elements: Long) {
                                if (elements > 0) demandObserved.set(true)
                                subscription.request(elements)
                            }

                            /** Propagates cancellation to the original server request. */
                            override fun cancel() = subscription.cancel()
                        },
                    )
                }

                /** Relays one request-body object unchanged. */
                override fun onNext(item: HttpObject) = subscriber.onNext(item)

                /** Relays the original request-body failure unchanged. */
                override fun onError(failure: Throwable) = subscriber.onError(failure)

                /** Relays original request-body completion unchanged. */
                override fun onComplete() = subscriber.onComplete()
            },
        )
    }
}
