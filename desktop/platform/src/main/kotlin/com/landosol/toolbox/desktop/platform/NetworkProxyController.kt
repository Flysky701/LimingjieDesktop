package com.landosol.toolbox.desktop.platform

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Install before constructing gateways: existing OkHttp clients retain this mutable selector. */
class NetworkProxyController(private val original: ProxySelector? = ProxySelector.getDefault()) : AutoCloseable {
    @Volatile private var mode = NetworkMode.SYSTEM
    private val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = if (mode == NetworkMode.DIRECT) listOf(Proxy.NO_PROXY)
            else original?.select(uri) ?: listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
            if (mode == NetworkMode.SYSTEM) original?.connectFailed(uri, address, failure)
        }
    }
    init { ProxySelector.setDefault(selector) }
    fun setMode(value: NetworkMode) { mode = value }
    override fun close() { if (ProxySelector.getDefault() === selector) ProxySelector.setDefault(original) }
}
