package io.vigilant.gateway.tracing

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.stream.SubscriptionOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Observes actual client delivery; finite demand can hold a large replay after its first body object. */
internal class TransportTraceStream(response: HttpResponse, demand: Long = Long.MAX_VALUE) {
    val headers = CompletableFuture<ResponseHeaders>()
    val firstBody = CompletableFuture<String>()
    val terminal = CompletableFuture<Throwable?>()
    private val subscription = CompletableFuture<Subscription>()
    private val parts = CopyOnWriteArrayList<String>()

    init {
        response.subscribe(object : Subscriber<HttpObject> {
            /** Requests only the scenario's allowed objects, without aggregating the response. */
            override fun onSubscribe(subscription: Subscription) {
                this@TransportTraceStream.subscription.complete(subscription)
                subscription.request(demand)
            }
            /** Publishes a header/body observation after copying the delivered object. */
            override fun onNext(item: HttpObject) {
                when (item) {
                    is ResponseHeaders -> headers.complete(item)
                    is HttpData -> {
                        val text = item.toStringUtf8()
                        parts.add(text)
                        if (text.isNotEmpty()) firstBody.complete(text)
                    }
                }
            }
            /** Publishes the observed client failure without inferring the owning server outcome. */
            override fun onError(cause: Throwable) { terminal.complete(cause) }
            /** Marks ordinary EOF only after all delivered body objects. */
            override fun onComplete() { terminal.complete(null) }
        }, SubscriptionOption.NOTIFY_CANCELLATION)
    }

    /** Cancels the subscribed response after a scenario has observed its first body item. */
    fun cancelSubscription() { subscription.join().cancel() }

    /** Returns the ordered bytes received so far as UTF-8 text for the ASCII fixture payloads. */
    fun body(): String = parts.joinToString("")
}
