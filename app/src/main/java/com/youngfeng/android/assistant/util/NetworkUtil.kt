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
        return when {
            isWifiConnected(context) -> "WiFi"
            isHotspotEnabled(context) -> "Hotspot"
            isEthernetConnected(context) -> "Ethernet"
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
        return getHotspotIpAddress()
    }

    /**
     * 获取热点模式下的IP地址
     */
    fun getHotspotIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val interfaceName = networkInterface.name.lowercase()
                
                // 热点接口通常是 wlan0, ap0, swlan0, tether 等
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
                                    Timber.d("Found hotspot IP: $hostAddress on interface $interfaceName")
                                    return hostAddress
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e("Error getting hotspot IP: ${e.message}")
        }
        
        // 返回默认热点IP
        return "192.168.43.1"
    }
}