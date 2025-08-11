package com.youngfeng.android.assistant.manager

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.Build
import android.text.format.Formatter
import com.youngfeng.android.assistant.Constants
import com.youngfeng.android.assistant.app.AirControllerApp
import com.youngfeng.android.assistant.model.Device
import com.youngfeng.android.assistant.model.LogType
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors

/**
 * 设备发现管理器，用于发现连接设备，使用UDP广播进行局域网搜索.
 *
 * @author Scott Smith 2021/11/20 21:46
 */
interface DeviceDiscoverManager {
    /**
     * 开始设备发现服务.
     */
    fun startDiscover()

    /**
     * @return 设备发现服务启动状态.
     */
    fun isStarted(): Boolean

    /**
     * 设备被发现回调.
     *
     * @param callback 设备发现回调.
     */
    fun onDeviceDiscover(callback: (device: Device) -> Unit)

    /**
     * 停止设备发现服务.
     */
    fun stopDiscover()

    companion object {
        private val instance = DeviceDiscoverManagerImpl()

        @JvmStatic
        fun getInstance() = instance
    }
}

/**
 * 设备发现管理类实现接口.
 */
class DeviceDiscoverManagerImpl : DeviceDiscoverManager {
    private var mReceiveSocket: DatagramSocket? = null
    private val mBroadcastInterfaces = mutableListOf<BroadcastInterface>()
    private var isStarted = false
    private var onDeviceDiscover: ((device: Device) -> Unit)? = null
    private val mTimer by lazy { Timer() }
    private var mMulticastLock: MulticastLock? = null

    private val mExecutor by lazy {
        Executors.newSingleThreadExecutor()
    }

    // 存储接口信息和对应的socket
    data class BroadcastInterface(
        val name: String,
        val ipAddress: String,
        val broadcastAddress: String,
        val socket: DatagramSocket
    )

    companion object {
        private const val TAG = "DeviceDiscoverManager"
        private const val MULTICAST_LOCK_TAG = "AirController:DeviceDiscovery"
    }

    override fun startDiscover() {
        mExecutor.submit {
            // 获取多播锁（在热点模式下必需）
            acquireMulticastLock()

            // 创建接收socket（只需要一个来接收响应）
            if (null == mReceiveSocket) {
                mReceiveSocket = DatagramSocket(Constants.Port.UDP_DEVICE_DISCOVER)
            }

            // 为每个网络接口创建发送socket
            setupBroadcastSockets()

            mTimer.schedule(
                object : TimerTask() {
                    override fun run() {
                        sendBroadcastMsg()
                    }
                },
                0, 1000
            )

            while (!isStarted) {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                mReceiveSocket?.receive(packet)

                val data = String(buffer)
                if (isValidResponse(data)) {
                    val device = convertToDevice(data)
                    Timber.d("当前设备：${device.name}, platform: ${device.platform}, ip address: ${device.ip}")
                    LogManager.log("发现设备: ${device.name} (${device.ip})", LogType.SUCCESS)
                } else {
                    Timber.d("It's not valid, data: $data")
                }
            }

            isStarted = true
        }
    }

    /**
     * 为每个网络接口创建广播socket
     */
    private fun setupBroadcastSockets() {
        try {
            // 清理旧的广播接口
            mBroadcastInterfaces.forEach { it.socket.close() }
            mBroadcastInterfaces.clear()

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // 跳过未启用或回环接口
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    Timber.d("Skipping interface ${networkInterface.name}: up=${networkInterface.isUp}, loopback=${networkInterface.isLoopback}")
                    continue
                }

                // 检查接口属性
                val interfaceName = networkInterface.name.lowercase()
                val supportsMulticast = try {
                    networkInterface.supportsMulticast()
                } catch (e: Exception) {
                    // 某些接口可能抛出异常，假设支持
                    Timber.w("Failed to check multicast support for $interfaceName: ${e.message}")
                    true
                }

                Timber.d("Processing interface $interfaceName, multicast support: $supportsMulticast")

                // 获取该接口的IP地址和广播地址
                val interfaceAddresses = networkInterface.interfaceAddresses
                for (interfaceAddress in interfaceAddresses) {
                    val address = interfaceAddress.address
                    val broadcast = interfaceAddress.broadcast

                    // 只处理IPv4地址
                    if (address != null && !address.isLoopbackAddress &&
                        address.hostAddress != null && !address.hostAddress.contains(":")
                    ) {

                        val hostAddress = address.hostAddress

                        // 跳过链路本地地址
                        if (hostAddress.startsWith("169.254.") || hostAddress.startsWith("127.")) {
                            Timber.d("Skipping link-local address: $hostAddress on $interfaceName")
                            continue
                        }

                        Timber.d("Found valid IP on $interfaceName: $hostAddress")

                        // 计算广播地址
                        val broadcastAddr = when {
                            broadcast != null -> broadcast.hostAddress
                            // 如果没有广播地址，根据子网掩码计算
                            else -> calculateBroadcastAddress(hostAddress, interfaceAddress.networkPrefixLength)
                        }

                        if (broadcastAddr != null) {
                            // 尝试创建并配置socket
                            var socket: DatagramSocket? = null
                            var isBound = false

                            try {
                                // 对于热点接口，尝试绑定到特定地址
                                if (interfaceName.contains("ap") || interfaceName.contains("swlan") ||
                                    interfaceName.contains("tether") || interfaceName.contains("softap")
                                ) {
                                    try {
                                        socket = DatagramSocket(null)
                                        socket.reuseAddress = true
                                        socket.broadcast = true
                                        val bindAddress = InetSocketAddress(address, 0)
                                        socket.bind(bindAddress)
                                        isBound = true
                                        Timber.d("Bound socket to $hostAddress on hotspot interface $interfaceName")
                                    } catch (e: Exception) {
                                        Timber.w("Failed to bind hotspot interface $interfaceName: ${e.message}")
                                        socket?.close()
                                        socket = null
                                    }
                                }

                                // 如果是WiFi接口或绑定失败，使用未绑定的socket
                                if (socket == null) {
                                    socket = DatagramSocket()
                                    socket.broadcast = true
                                    Timber.d("Created unbound socket for $interfaceName")
                                }

                                val broadcastInterface = BroadcastInterface(
                                    name = interfaceName,
                                    ipAddress = hostAddress,
                                    broadcastAddress = broadcastAddr,
                                    socket = socket
                                )

                                mBroadcastInterfaces.add(broadcastInterface)
                                val bindStatus = if (isBound) "已绑定" else "未绑定"
                                Timber.d("Added broadcast interface: $interfaceName, IP: $hostAddress, Broadcast: $broadcastAddr ($bindStatus)")
                                LogManager.log("添加广播接口($bindStatus): $interfaceName ($hostAddress -> $broadcastAddr)", LogType.NETWORK)
                            } catch (e: Exception) {
                                Timber.e("Failed to create socket for $interfaceName: ${e.message}")
                                socket?.close()
                            }
                        }
                    }
                }
            }

            // 如果没有找到合适的接口，创建一个默认的
            if (mBroadcastInterfaces.isEmpty()) {
                try {
                    val defaultSocket = DatagramSocket()
                    defaultSocket.broadcast = true

                    val defaultInterface = BroadcastInterface(
                        name = "default",
                        ipAddress = "0.0.0.0",
                        broadcastAddress = "255.255.255.255",
                        socket = defaultSocket
                    )

                    mBroadcastInterfaces.add(defaultInterface)
                    Timber.d("Created default broadcast interface")
                } catch (e: Exception) {
                    Timber.e("Failed to create default socket: ${e.message}")
                }
            }

            Timber.d("Total broadcast interfaces: ${mBroadcastInterfaces.size}")
        } catch (e: Exception) {
            Timber.e("Error setting up broadcast sockets: ${e.message}")
        }
    }

    /**
     * 根据IP地址和前缀长度计算广播地址
     */
    private fun calculateBroadcastAddress(ipAddress: String, prefixLength: Short): String {
        return try {
            val parts = ipAddress.split(".")
            if (parts.size != 4) return "255.255.255.255"

            val ip = parts.map { it.toInt() }.toIntArray()
            val netmask = (-1 shl (32 - prefixLength)).toInt()

            val broadcast = IntArray(4)
            for (i in 0..3) {
                val maskByte = (netmask shr (24 - i * 8)) and 0xFF
                broadcast[i] = ip[i] or (maskByte.inv() and 0xFF)
            }

            broadcast.joinToString(".")
        } catch (e: Exception) {
            Timber.e("Error calculating broadcast address: ${e.message}")
            "255.255.255.255"
        }
    }

    private fun convertToDevice(data: String): Device {
        val realInfo = data.replace("${Constants.SEARCH_RES_PREFIX}${Constants.RADNOM_STR_RES_SEARCH}#", "")

        val arr = realInfo.split("#")
        var platform = -1
        if (arr.isNotEmpty()) {
            platform = arr[0].toIntOrNull() ?: -1
        }

        var name = ""
        if (arr.size > 1) {
            name = arr[1]
        }

        var ip = ""
        if (arr.size > 2) {
            ip = arr[2]
        }

        return Device(name, ip, platform)
    }

    private fun isValidResponse(data: String): Boolean {
        return data.startsWith("${Constants.SEARCH_RES_PREFIX}${Constants.RADNOM_STR_RES_SEARCH}#")
    }

    @SuppressLint("WifiManagerLeak")
    private fun sendBroadcastMsg() {
        try {
            val name = Build.MODEL

            // 在所有接口上发送广播
            var sentCount = 0
            val sentDetails = mutableListOf<String>()

            for (broadcastInterface in mBroadcastInterfaces) {
                try {
                    // 使用该接口的IP地址
                    val searchCmd = "${Constants.SEARCH_PREFIX}${
                    Constants
                        .RANDOM_STR_SEARCH
                    }#${Constants.PLATFORM_ANDROID}#$name#${broadcastInterface.ipAddress}"

                    val cmdByteArray = searchCmd.toByteArray()

                    // 使用该接口特定的广播地址
                    val address = InetSocketAddress(
                        broadcastInterface.broadcastAddress,
                        Constants.Port.UDP_DEVICE_DISCOVER
                    )
                    val packet = DatagramPacket(cmdByteArray, cmdByteArray.size, address)

                    broadcastInterface.socket.send(packet)
                    sentCount++

                    val detail = "${broadcastInterface.name}(${broadcastInterface.ipAddress}->${broadcastInterface.broadcastAddress})"
                    sentDetails.add(detail)

                    Timber.d("Sent broadcast on ${broadcastInterface.name}: ${broadcastInterface.ipAddress} -> ${broadcastInterface.broadcastAddress}")
                } catch (e: Exception) {
                    Timber.e("Failed to send on ${broadcastInterface.name}: ${e.message}")
                }
            }

            if (sentCount > 0) {
                Timber.d("Successfully sent broadcast on $sentCount interfaces")
                LogManager.log("发送广播: ${sentDetails.joinToString(", ")}", LogType.NETWORK)
            } else {
                Timber.w("Failed to send broadcast on any interface")
                LogManager.log("广播发送失败", LogType.ERROR)

                // 如果没有成功发送，尝试重新设置
                if (mBroadcastInterfaces.isNotEmpty()) {
                    Timber.w("Recreating broadcast interfaces")
                    setupBroadcastSockets()
                }
            }
        } catch (e: Exception) {
            Timber.e("Error in sendBroadcastMsg: ${e.message}")
        }
    }

    /**
     * 获取设备IP地址，支持WiFi连接和热点模式
     */
    @SuppressLint("WifiManagerLeak")
    private fun getDeviceIpAddress(): String {
        val context = AirControllerApp.getInstance()
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 先尝试获取WiFi连接的IP地址
        val wifiIp = wifiManager.connectionInfo.ipAddress
        if (wifiIp != 0) {
            val formattedIp = Formatter.formatIpAddress(wifiIp)
            if (formattedIp != "0.0.0.0") {
                Timber.d("Using WiFi IP: $formattedIp")
                return formattedIp
            }
        }

        // 如果WiFi IP无效，尝试获取热点模式的IP地址
        return getHotspotIpAddressFromInterface()
    }

    /**
     * 从网络接口获取热点IP地址
     */
    private fun getHotspotIpAddressFromInterface(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // 跳过未启用或回环接口
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress
                        // 过滤IPv6地址和链路本地地址
                        if (hostAddress != null &&
                            !hostAddress.contains(":") &&
                            !hostAddress.startsWith("127.") &&
                            !hostAddress.startsWith("169.254.")
                        ) {

                            // 返回找到的第一个有效的IPv4地址
                            Timber.d("Found IP from interface ${networkInterface.name}: $hostAddress")
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e("Error getting network interfaces: ${e.message}")
        }

        // 如果没有找到任何有效IP，返回0.0.0.0表示失败
        Timber.w("Could not determine device IP address")
        return "0.0.0.0"
    }

    override fun isStarted(): Boolean {
        return isStarted
    }

    override fun onDeviceDiscover(callback: (device: Device) -> Unit) {
        this.onDeviceDiscover = callback
    }

    override fun stopDiscover() {
        mReceiveSocket?.close()
        mBroadcastInterfaces.forEach { it.socket.close() }
        mBroadcastInterfaces.clear()
        mTimer.cancel()
        mExecutor.shutdownNow()
        isStarted = false

        // 释放多播锁
        releaseMulticastLock()
    }

    /**
     * 获取多播锁，在热点模式下发送广播需要
     */
    @SuppressLint("WifiManagerLeak")
    private fun acquireMulticastLock() {
        try {
            val context = AirControllerApp.getInstance()
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (mMulticastLock == null) {
                mMulticastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG)
                mMulticastLock?.setReferenceCounted(false)
            }

            mMulticastLock?.acquire()
            Timber.d("Multicast lock acquired")
            LogManager.log("获取多播锁成功", LogType.NETWORK)
        } catch (e: Exception) {
            Timber.e("Failed to acquire multicast lock: ${e.message}")
            LogManager.log("获取多播锁失败: ${e.message}", LogType.ERROR)
        }
    }

    /**
     * 释放多播锁
     */
    private fun releaseMulticastLock() {
        try {
            if (mMulticastLock?.isHeld == true) {
                mMulticastLock?.release()
                Timber.d("Multicast lock released")
                LogManager.log("释放多播锁", LogType.NETWORK)
            }
            mMulticastLock = null
        } catch (e: Exception) {
            Timber.e("Failed to release multicast lock: ${e.message}")
        }
    }
}
