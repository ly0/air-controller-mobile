package com.youngfeng.android.assistant.manager

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import com.youngfeng.android.assistant.Constants
import com.youngfeng.android.assistant.app.AirControllerApp
import com.youngfeng.android.assistant.model.Device
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
    private var mDatagramSocket: DatagramSocket? = null
    private var isStarted = false
    private var onDeviceDiscover: ((device: Device) -> Unit)? = null
    private val mTimer by lazy { Timer() }

    private val mExecutor by lazy {
        Executors.newSingleThreadExecutor()
    }

    companion object {
        private const val TAG = "DeviceDiscoverManager"
    }

    override fun startDiscover() {
        mExecutor.submit {
            if (null == mDatagramSocket) {
                mDatagramSocket = DatagramSocket(Constants.Port.UDP_DEVICE_DISCOVER)
            }

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
                mDatagramSocket?.receive(packet)

                val data = String(buffer)
                if (isValidResponse(data)) {
                    val device = convertToDevice(data)
                    Timber.d("当前设备：${device.name}, platform: ${device.platform}, ip address: ${device.ip}")
                } else {
                    Timber.d("It's not valid, data: $data")
                }
            }

            isStarted = true
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
            val ip = getDeviceIpAddress()
            val name = Build.MODEL

            val searchCmd = "${Constants.SEARCH_PREFIX}${
            Constants
                .RANDOM_STR_SEARCH
            }#${Constants.PLATFORM_ANDROID}#$name#$ip"

            val cmdByteArray = searchCmd.toByteArray()

            val address = InetSocketAddress("255.255.255.255", Constants.Port.UDP_DEVICE_DISCOVER)
            val packet = DatagramPacket(cmdByteArray, cmdByteArray.size, address)

            Timber.d("Send broadcast msg to 255.255.255.255, device IP: $ip")
            mDatagramSocket?.send(packet)
        } catch (e: Exception) {
            Timber.e(e.message ?: "Unknown error")
        }
    }

    /**
     * 获取设备IP地址，支持WiFi连接和热点模式
     */
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
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val interfaceName = networkInterface.name.lowercase()
                
                // 热点接口通常是 wlan0, ap0, swlan0 等
                if (interfaceName.contains("wlan") || interfaceName.contains("ap") || 
                    interfaceName.contains("swlan") || interfaceName.contains("tether")) {
                    
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is InetAddress) {
                            val hostAddress = address.hostAddress
                            // 过滤IPv6地址和链路本地地址
                            if (hostAddress != null && !hostAddress.contains(":") && 
                                !hostAddress.startsWith("127.") && !hostAddress.startsWith("169.254.")) {
                                // 热点IP通常是 192.168.43.1 或 192.168.49.1
                                if (hostAddress.startsWith("192.168.")) {
                                    Timber.d("Using Hotspot IP from interface $interfaceName: $hostAddress")
                                    return hostAddress
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e("Error getting network interfaces: ${e.message}")
        }

        // 如果还是没有找到，返回默认值
        Timber.w("Could not determine device IP address, using default")
        return "0.0.0.0"
    }

    private fun isValidData(data: String): Boolean {
        return data.startsWith("${Constants.SEARCH_PREFIX}${Constants.RANDOM_STR_SEARCH}#")
    }

    override fun isStarted(): Boolean {
        return isStarted
    }

    override fun onDeviceDiscover(callback: (device: Device) -> Unit) {
        this.onDeviceDiscover = callback
    }

    override fun stopDiscover() {
        mDatagramSocket?.close()
        mTimer.cancel()
        mExecutor.shutdownNow()
        isStarted = false
    }
}
