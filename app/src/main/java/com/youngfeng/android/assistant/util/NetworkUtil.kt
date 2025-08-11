package com.youngfeng.android.assistant.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import timber.log.Timber
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtil {

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
        }
    }

    fun isHotspotEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        return try {
            val method: Method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            Timber.e("Error checking hotspot status: ${e.message}")
            false
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return isWifiConnected(context) || isHotspotEnabled(context) || isEthernetConnected(context)
    }

    private fun isEthernetConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.type == ConnectivityManager.TYPE_ETHERNET && networkInfo.isConnected
        }
    }

    fun getNetworkStatus(context: Context): String {
        val isWifi = isWifiConnected(context)
        val isHotspot = isHotspotEnabled(context)
        val isEthernet = isEthernetConnected(context)

        return when {
            isWifi && isHotspot -> "WiFi+Hotspot"
            isWifi -> "WiFi"
            isHotspot -> "Hotspot"
            isEthernet -> "Ethernet"
            else -> "No Network"
        }
    }

    /**
     * 获取设备当前IP地址，支持WiFi和热点模式
     */
    fun getDeviceIpAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 先尝试获取WiFi连接的IP地址
        val wifiIp = wifiManager.connectionInfo.ipAddress
        if (wifiIp != 0) {
            val formattedIp = Formatter.formatIpAddress(wifiIp)
            if (formattedIp != "0.0.0.0") {
                return formattedIp
            }
        }

        // 如果WiFi IP无效，尝试获取热点模式的IP地址
        return getHotspotIpAddressFromInterface()
    }

    /**
     * 获取WiFi连接的IP地址
     */
    fun getWifiIpAddress(context: Context): String? {
        if (!isWifiConnected(context)) {
            return null
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiIp = wifiManager.connectionInfo.ipAddress
        if (wifiIp != 0) {
            val formattedIp = Formatter.formatIpAddress(wifiIp)
            if (formattedIp != "0.0.0.0") {
                return formattedIp
            }
        }
        return null
    }

    /**
     * 获取热点模式下的IP地址，返回null表示热点未开启
     */
    fun getHotspotIpAddress(context: Context): String? {
        if (!isHotspotEnabled(context)) {
            return null
        }
        return getHotspotIpAddressFromInterface()
    }

    /**
     * 从网络接口获取热点IP地址
     */
    private fun getHotspotIpAddressFromInterface(): String {
        try {
            // 收集所有可能的IP地址
            val possibleIPs = mutableListOf<Pair<String, String>>() // interface name to IP

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // 跳过未启用或回环接口
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val interfaceName = networkInterface.name.lowercase()

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

                            possibleIPs.add(interfaceName to hostAddress)
                            Timber.d("Found IP: $hostAddress on interface $interfaceName")
                        }
                    }
                }
            }

            // 优先返回特定的热点接口
            val hotspotInterfaces = listOf("ap0", "swlan0", "wlan1", "softap0", "tether")
            for ((ifName, ip) in possibleIPs) {
                if (hotspotInterfaces.any { ifName.contains(it) }) {
                    Timber.d("Using hotspot IP from interface $ifName: $ip")
                    return ip
                }
            }

            // 如果没有找到特定接口，返回任何非wlan0的无线接口IP
            for ((ifName, ip) in possibleIPs) {
                if (ifName.contains("wlan") && ifName != "wlan0") {
                    Timber.d("Using IP from wireless interface $ifName: $ip")
                    return ip
                }
            }

            // 如果还是没有，返回任何找到的有效IP（排除已知的客户端接口）
            for ((ifName, ip) in possibleIPs) {
                // 排除eth0（以太网）和wlan0（通常是WiFi客户端）
                if (ifName != "eth0" && ifName != "wlan0") {
                    Timber.d("Using IP from interface $ifName: $ip")
                    return ip
                }
            }

            // 如果只有wlan0，也返回它（某些设备热点也用wlan0）
            for ((ifName, ip) in possibleIPs) {
                if (ifName == "wlan0") {
                    Timber.d("Using IP from wlan0 (might be hotspot): $ip")
                    return ip
                }
            }
        } catch (e: Exception) {
            Timber.e("Error getting hotspot IP: ${e.message}")
        }

        // 没有找到任何有效IP
        return "N/A"
    }
}
