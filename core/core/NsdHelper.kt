package com.vlasovs.chat.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class NsdPeer(val name: String, val host: InetAddress, val port: Int)

class NsdHelper(
    private val context: Context,
    private val serviceType: String,
    private val serviceName: String
) {
    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val wifi by lazy { context.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val peersMap = ConcurrentHashMap<String, NsdPeer>()
    private val _peers = MutableStateFlow<List<NsdPeer>>(emptyList())
    val peers: StateFlow<List<NsdPeer>> get() = _peers

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = serviceName
            serviceType = serviceType // must end with "."
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registration = listener
    }

    fun startDiscovery() {
        multicastLock = wifi.createMulticastLock("vlasovschat").apply { setReferenceCounted(true); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {
                multicastLock?.release(); multicastLock = null
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != serviceType) return
                if (serviceInfo.serviceName == serviceName) return // skip self
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host ?: return
                        val port = info.port
                        val name = info.serviceName
                        peersMap[name] = NsdPeer(name, host, port)
                        scope.launch { _peers.emit(peersMap.values.sortedBy { it.name }) }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peersMap.remove(serviceInfo.serviceName)
                scope.launch { _peers.emit(peersMap.values.sortedBy { it.name }) }
            }
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        discovery = listener
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        multicastLock?.release()
        multicastLock = null
    }

    fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }
}
