package com.youngfeng.android.assistant.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.youngfeng.android.assistant.event.RequestDisconnectClientEvent
import com.youngfeng.android.assistant.model.DesktopInfo
import com.youngfeng.android.assistant.model.LogEntry
import com.youngfeng.android.assistant.model.LogType
import com.youngfeng.android.assistant.socket.CmdSocketServer
import org.greenrobot.eventbus.EventBus

class HomeViewModel : ViewModel() {
    private val _isWifiConnected = MutableLiveData<Boolean>()
    val isWifiConnected: LiveData<Boolean> = _isWifiConnected

    private val _isDeviceConnected = MutableLiveData(false)
    val isDeviceConnected: LiveData<Boolean> = _isDeviceConnected

    private val _deviceName = MutableLiveData<String>()
    val deviceName: LiveData<String> = _deviceName

    private val _wlanName = MutableLiveData<String>()
    val wlanName: LiveData<String> = _wlanName

    private val _desktopInfo = MutableLiveData<DesktopInfo>()
    val desktopInfo: LiveData<DesktopInfo> = _desktopInfo

    private val _isAllPermissionsGranted = MutableLiveData<Boolean>()
    val isAllPermissionsGranted: LiveData<Boolean> = _isAllPermissionsGranted

    private val _wifiIpAddress = MutableLiveData<String>()
    val wifiIpAddress: LiveData<String> = _wifiIpAddress

    private val _hotspotIpAddress = MutableLiveData<String>()
    val hotspotIpAddress: LiveData<String> = _hotspotIpAddress

    private val _networkMode = MutableLiveData<String>()
    val networkMode: LiveData<String> = _networkMode

    private var previousNetworkMode: String? = null

    private val _logEntries = MutableLiveData<MutableList<LogEntry>>(mutableListOf())
    val logEntries: LiveData<MutableList<LogEntry>> = _logEntries

    private val _isLoggingEnabled = MutableLiveData<Boolean>(true)
    val isLoggingEnabled: LiveData<Boolean> = _isLoggingEnabled

    fun setWifiConnectStatus(isConnected: Boolean) {
        _isWifiConnected.value = isConnected
    }

    fun setDeviceConnected(isConnected: Boolean) {
        _isDeviceConnected.value = isConnected
    }

    fun setDeviceName(deviceName: String) {
        _deviceName.value = deviceName
    }

    fun setWlanName(wlanName: String) {
        _wlanName.value = wlanName
    }

    fun setDesktopInfo(desktopInfo: DesktopInfo) {
        _desktopInfo.value = desktopInfo
    }

    fun updateAllPermissionsGranted(isGranted: Boolean) {
        _isAllPermissionsGranted.value = isGranted
    }

    fun setWifiIpAddress(ipAddress: String?) {
        _wifiIpAddress.value = ipAddress ?: "N/A"
    }

    fun setHotspotIpAddress(ipAddress: String?) {
        _hotspotIpAddress.value = ipAddress ?: "N/A"
    }

    fun setNetworkMode(mode: String) {
        _networkMode.value = mode
    }

    fun setNetworkModeWithLog(mode: String) {
        // 只在网络状态真正改变时输出日志
        if (previousNetworkMode != mode) {
            previousNetworkMode = mode
            _networkMode.value = mode
            addLogEntry("网络状态: $mode", LogType.NETWORK)

            // 网络状态改变时，更新广播配置
            com.youngfeng.android.assistant.manager.DeviceDiscoverManager.getInstance().updateNetworkConfiguration()
        } else {
            // 即使状态没变，也更新LiveData以保持UI同步
            _networkMode.value = mode
        }
    }

    fun disconnect() {
        CmdSocketServer.getInstance().disconnect()
        EventBus.getDefault().post(RequestDisconnectClientEvent())
        _isDeviceConnected.value = false
    }

    fun addLogEntry(message: String, type: LogType = LogType.INFO) {
        // 只有在日志开关打开时才添加日志
        if (_isLoggingEnabled.value == true) {
            val currentList = _logEntries.value ?: mutableListOf()
            currentList.add(0, LogEntry(message = message, type = type))

            // Keep only the latest 100 entries
            if (currentList.size > 100) {
                currentList.removeAt(currentList.size - 1)
            }

            _logEntries.value = currentList
        }
    }

    fun setLoggingEnabled(enabled: Boolean) {
        val wasEnabled = _isLoggingEnabled.value ?: true
        _isLoggingEnabled.value = enabled

        // 只在状态改变时输出日志
        if (wasEnabled != enabled) {
            if (enabled) {
                // 因为日志被关闭了，所以这条消息需要先设置状态再添加
                addLogEntry("日志记录已开启", LogType.INFO)
            } else {
                // 在关闭之前添加这条消息
                _isLoggingEnabled.value = true
                addLogEntry("日志记录已关闭", LogType.WARNING)
                _isLoggingEnabled.value = false
            }
        }
    }

    fun clearLogs() {
        _logEntries.value = mutableListOf()
    }
}
