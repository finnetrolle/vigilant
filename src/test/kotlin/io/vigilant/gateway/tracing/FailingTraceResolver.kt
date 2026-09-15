package io.vigilant.gateway.tracing

import io.netty.resolver.AbstractAddressResolver
import io.netty.resolver.AddressResolver
import io.netty.resolver.AddressResolverGroup
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.Promise
import java.net.InetSocketAddress
import java.net.UnknownHostException

/** Controlled DNS failure: no external lookup or environment-dependent invalid-host assumption. */
internal class FailingTraceResolver : AddressResolverGroup<InetSocketAddress>() {
    /** Installs a resolver owned and closed by the existing client factory lifecycle. */
    override fun newResolver(executor: EventExecutor): AddressResolver<InetSocketAddress> =
        object : AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress::class.java) {
            /** Already resolved loopback addresses bypass DNS. */
            override fun doIsResolved(address: InetSocketAddress): Boolean = !address.isUnresolved
            /** Fails one resolution at the actual network dependency boundary. */
            override fun doResolve(address: InetSocketAddress, promise: Promise<InetSocketAddress>) {
                promise.setFailure(UnknownHostException("synthetic-message-42"))
            }
            /** Fails all-address resolution with the same controlled dependency stimulus. */
            override fun doResolveAll(address: InetSocketAddress, promise: Promise<MutableList<InetSocketAddress>>) {
                promise.setFailure(UnknownHostException("synthetic-message-42"))
            }
        }
}
