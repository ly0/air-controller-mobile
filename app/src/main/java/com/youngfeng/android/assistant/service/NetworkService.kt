package com.youngfeng.android.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.ON_AFTER_RELEASE
import android.os.PowerManager.PARTIAL_WAKE_LOCK
import android.widget.RemoteViews
import com.google.gson.Gson
import com.youngfeng.android.assistant.Constants
import com.youngfeng.android.assistant.MainActivity
import com.youngfeng.android.assistant.R
import com.youngfeng.android.assistant.event.AirControllerStateEvent
import com.youngfeng.android.assistant.event.DeviceConnectEvent
import com.youngfeng.android.assistant.event.DeviceDisconnectEvent
import com.youngfeng.android.assistant.event.DeviceReportEvent
import com.youngfeng.android.assistant.event.RequestDisconnectClientEvent
import com.youngfeng.android.assistant.manager.DeviceDiscoverManager
import com.youngfeng.android.assistant.manager.LogEvent
import com.youngfeng.android.assistant.model.Command
import com.youngfeng.android.assistant.model.Device
import com.youngfeng.android.assistant.model.LogEntry
import com.youngfeng.android.assistant.model.LogType
import com.youngfeng.android.assistant.model.MobileInfo
import com.youngfeng.android.assistant.server.IpWhitelistManager
import com.youngfeng.android.assistant.server.configureKtorServer
import com.youngfeng.android.assistant.socket.CmdSocketServer
import com.youngfeng.android.assistant.socket.heartbeat.HeartbeatClient
import com.youngfeng.android.assistant.socket.heartbeat.HeartbeatListener
import com.youngfeng.android.assistant.socket.heartbeat.HeartbeatServerPlus
import com.youngfeng.android.assistant.util.CommonUtil
import com.youngfeng.android.assistant.util.NetworkUtil
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import timber.log.Timber

class NetworkService : Service() {
    private val mBatteryReceiver by lazy {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    notifyBatteryChanged()
                }
            }
        }

        receiver
    }

    private val mToggleReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_TOGGLE_SERVICE) {
                    toggleService()
                }
            }
        }
    }

    private var mHttpServer: ApplicationEngine? = null

    private lateinit var mWakeLock: PowerManager.WakeLock
    private lateinit var heartbeatServer: HeartbeatServerPlus
    private val mGson by lazy { Gson() }
    private var isAirControllerEnabled = true
    private var isToggleInProgress = false
    private var connectedDevice: Device? = null
    private var isDeviceConnected = false
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hotspotCheckTimer: java.util.Timer? = null

    private companion object {
        const val DEFAULT_TIMEOUT = 10
        const val RC_NOTIFICATION = 0x1001
        const val RC_TOGGLE = 0x1002
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE_SERVICE = "com.youngfeng.android.assistant.ACTION_TOGGLE_SERVICE"
        const val CHANNEL_ID = "AirControllerChannel"
    }

    override fun onCreate() {
        super.onCreate()

        mWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            ON_AFTER_RELEASE or PARTIAL_WAKE_LOCK,
            "AirController:NetworkService"
        )
        mWakeLock.acquire(60 * 60 * 1000L)

        // 从SharedPreferences读取服务状态
        val prefs = getSharedPreferences("AirControllerPrefs", Context.MODE_PRIVATE)
        isAirControllerEnabled = prefs.getBoolean("service_enabled", true)

        // 初始化IP白名单（清空旧记录，默认启用）
        IpWhitelistManager.clear()
        Timber.d("IP whitelist initialized")
        Timber.d("Service initialized with enabled state: $isAirControllerEnabled")

        // 只有在服务启用时才启动各项功能
        if (isAirControllerEnabled) {
            CmdSocketServer.getInstance().onOpen = {
                updateMobileInfo()
            }
            CmdSocketServer.getInstance().onCommandReceive {
                processCmd(it)
            }
            CmdSocketServer.getInstance().start()

            heartbeatServer = HeartbeatServerPlus.create()
            heartbeatServer.addListener(object : HeartbeatListener() {
                override fun onStart() {
                    super.onStart()

                    Timber.d("Heartbeat server start success.")
                }

                override fun onStop() {
                    super.onStop()

                    Timber.d("Heartbeat server stop success.")
                }

                override fun onClientTimeout(client: HeartbeatClient) {
                    super.onClientTimeout(client)

                    // 客户端超时，恢复广播
                    DeviceDiscoverManager.getInstance().resumeBroadcast()

                    // 从IP白名单中移除该IP
                    val clientIp = heartbeatServer.getConnectedClientIp()
                    if (clientIp != null) {
                        IpWhitelistManager.removeIp(clientIp)
                        Timber.d("Removed timeout client IP from whitelist: $clientIp")
                    }

                    isDeviceConnected = false
                    connectedDevice = null
                    updateNotification() // 更新通知显示未连接状态
                    EventBus.getDefault().post(DeviceDisconnectEvent())
                    Timber.d("Heartbeat server, onClientTimeout.")
                }

                override fun onClientConnected(client: HeartbeatClient?) {
                    super.onClientConnected(client)

                    // 连接成功，暂停广播
                    DeviceDiscoverManager.getInstance().pauseBroadcast()

                    // 添加IP到白名单（白名单默认已启用）
                    val clientIp = heartbeatServer.getConnectedClientIp()
                    if (clientIp != null) {
                        IpWhitelistManager.addIp(clientIp)
                        Timber.d("Added connected client IP to whitelist: $clientIp")
                    }

                    isDeviceConnected = true
                    updateNotification() // 更新通知显示已连接状态
                    EventBus.getDefault().post(DeviceConnectEvent())
                    Timber.d("Heartbeat server, onNewClientJoin.")
                }

                override fun onClientDisconnected() {
                    super.onClientDisconnected()

                    // 断开连接，恢复广播
                    DeviceDiscoverManager.getInstance().resumeBroadcast()

                    // 从IP白名单中移除该IP
                    val clientIp = heartbeatServer.getConnectedClientIp()
                    if (clientIp != null) {
                        IpWhitelistManager.removeIp(clientIp)
                        Timber.d("Removed disconnected client IP from whitelist: $clientIp")
                    }

                    isDeviceConnected = false
                    connectedDevice = null
                    updateNotification() // 更新通知显示未连接状态
                    EventBus.getDefault().post(DeviceDisconnectEvent())
                }
            })
            heartbeatServer.start()

            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(mBatteryReceiver, filter)

            mHttpServer = embeddedServer(Netty, port = Constants.Port.HTTP_SERVER) {
                configureKtorServer(this@NetworkService)
            }
            mHttpServer?.start(wait = false)

            DeviceDiscoverManager.getInstance().onDeviceDiscover {
                Timber.d("Device: ip => ${it.ip}, name => ${it.name}, platform => ${it.platform}")
            }
            DeviceDiscoverManager.getInstance().startDiscover()
        } else {
            Timber.d("Service created but not enabled, skipping initialization")
        }

        registerEventBus()

        // Register toggle receiver
        val toggleFilter = IntentFilter(ACTION_TOGGLE_SERVICE)
        registerReceiver(mToggleReceiver, toggleFilter)

        // Register network state listener
        registerNetworkListener()

        // Start hotspot check timer
        startHotspotCheckTimer()
    }

    private fun notifyBatteryChanged() {
        updateMobileInfo()
    }

    // 获取手机电池电量以及内存使用情况，通知桌面客户端
    private fun updateMobileInfo() {
        val batteryLevel = CommonUtil.getBatteryLevel(this)
        val storageSize = CommonUtil.getExternalStorageSize()

        val mobileInfo = MobileInfo(batteryLevel, storageSize)

        val cmd = Command(Command.CMD_UPDATE_MOBILE_INFO, mobileInfo)
        CmdSocketServer.getInstance().sendCmd(cmd)
    }

    private fun processCmd(cmd: Command<Any>) {
        if (cmd.cmd == Command.CMD_REPORT_DESKTOP_INFO) {
            val str = mGson.toJson(cmd.data)
            val device = mGson.fromJson(str, Device::class.java)

            Timber.d("Cmd received, cmd: $cmd, device name: ${device.name}")
            connectedDevice = device
            updateNotification() // 更新通知显示设备信息
            EventBus.getDefault().post(DeviceReportEvent(device))
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRequestDisconnectClient(event: RequestDisconnectClientEvent) {
        if (this::heartbeatServer.isInitialized) {
            // 先获取客户端IP
            val clientIp = heartbeatServer.getConnectedClientIp()

            this.heartbeatServer.disconnectClient()
            // 主动断开连接，恢复广播
            DeviceDiscoverManager.getInstance().resumeBroadcast()

            // 从IP白名单中移除该IP
            if (clientIp != null) {
                IpWhitelistManager.removeIp(clientIp)
                Timber.d("Removed manually disconnected client IP from whitelist: $clientIp")
            }

            isDeviceConnected = false
            connectedDevice = null
            updateNotification() // 更新通知显示未连接状态
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onAirControllerStateChanged(event: AirControllerStateEvent) {
        if (event.enabled) {
            // 启用AirController - 重新启动所有服务
            isAirControllerEnabled = true
            saveServiceState(true)
            enableServices()
            isToggleInProgress = false
            updateNotification()
        } else {
            // 禁用AirController - 停止所有服务
            isAirControllerEnabled = false
            saveServiceState(false)
            disableServices()
            isToggleInProgress = false
            updateNotification()
        }
    }

    private fun disableServices() {
        Timber.d("Disabling AirController services...")

        // 停止设备发现
        DeviceDiscoverManager.getInstance().stopDiscover()

        // 停止心跳服务器
        if (this::heartbeatServer.isInitialized && heartbeatServer.isStarted()) {
            heartbeatServer.stop()
        }

        // 停止命令Socket服务器
        if (CmdSocketServer.getInstance().isStarted()) {
            CmdSocketServer.getInstance().stop()
        }

        // 停止HTTP服务器
        mHttpServer?.stop(1000, 2000)
        mHttpServer = null

        // 取消注册电池监听
        try {
            unregisterReceiver(mBatteryReceiver)
        } catch (e: Exception) {
            Timber.e("Error unregistering battery receiver: ${e.message}")
        }

        // 停止热点检查定时器
        stopHotspotCheckTimer()

        Timber.d("AirController services disabled")
    }

    private fun enableServices() {
        Timber.d("Enabling AirController services...")

        // 延迟启动，确保之前的服务已完全停止
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            enableServicesInternal()
        }, 500)
    }

    private fun enableServicesInternal() {
        Timber.d("Actually enabling AirController services...")

        // 重新启动命令Socket服务器 - 需要先检查是否已停止
        if (!CmdSocketServer.getInstance().isStarted()) {
            CmdSocketServer.getInstance().onOpen = {
                updateMobileInfo()
            }
            CmdSocketServer.getInstance().onCommandReceive {
                processCmd(it)
            }
            CmdSocketServer.getInstance().start()
            Timber.d("CmdSocketServer restarted")
        } else {
            Timber.d("CmdSocketServer already running")
        }

        // 重新启动心跳服务器 - 总是重新创建以确保干净的状态
        if (this::heartbeatServer.isInitialized && heartbeatServer.isStarted()) {
            heartbeatServer.stop()
            Thread.sleep(100) // 等待停止完成
        }

        // 创建新的心跳服务器实例
        if (!this::heartbeatServer.isInitialized || !heartbeatServer.isStarted()) {
            heartbeatServer = HeartbeatServerPlus.create()
            heartbeatServer.addListener(object : HeartbeatListener() {
                override fun onStart() {
                    super.onStart()
                    Timber.d("Heartbeat server start success.")
                }

                override fun onStop() {
                    super.onStop()
                    Timber.d("Heartbeat server stop success.")
                }

                override fun onClientTimeout(client: HeartbeatClient) {
                    super.onClientTimeout(client)
                    DeviceDiscoverManager.getInstance().resumeBroadcast()
                    val clientIp = heartbeatServer.getConnectedClientIp()
                    if (clientIp != null) {
                        IpWhitelistManager.removeIp(clientIp)
                        Timber.d("Removed timeout client IP from whitelist: $clientIp")
                    }
                    isDeviceConnected = false
                    connectedDevice = null
                    updateNotification()
                    EventBus.getDefault().post(DeviceDisconnectEvent())
                    Timber.d("Heartbeat server, onClientTimeout.")
                }

                override fun onClientConnected(client: HeartbeatClient?) {
                    super.onClientConnected(client)
                    DeviceDiscoverManager.getInstance().pauseBroadcast()
                    val clientIp = heartbeatServer.getConnectedClientIp()
                    if (clientIp != null) {
                        IpWhitelistManager.addIp(clientIp)
                        Timber.d("Added connected client IP to whitelist: $clientIp")
                    }
                    isDeviceConnected = true
                    updateNotification()
                    EventBus.getDefault().post(DeviceConnectEvent())
                    Timber.d("Heartbeat server, onNewClientJoin.")
                }

                override fun onClientDisconnected() {
                    super.onClientDisconnected()
                    DeviceDiscoverManager.getInstance().resumeBroadcast()
                    val clientIp = heartbeatServer.getConnectedClientIp()
                    if (clientIp != null) {
                        IpWhitelistManager.removeIp(clientIp)
                        Timber.d("Removed disconnected client IP from whitelist: $clientIp")
                    }
                    isDeviceConnected = false
                    connectedDevice = null
                    updateNotification()
                    EventBus.getDefault().post(DeviceDisconnectEvent())
                }
            })
            heartbeatServer.start()
            Timber.d("HeartbeatServer created and started")
        } else {
            Timber.d("HeartbeatServer already running")
        }

        // 重新注册电池监听
        try {
            // 先尝试取消注册，避免重复注册
            try {
                unregisterReceiver(mBatteryReceiver)
            } catch (e: Exception) {
                // 忽略未注册的错误
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(mBatteryReceiver, filter)
            Timber.d("Battery receiver registered")
        } catch (e: Exception) {
            Timber.e("Error registering battery receiver: ${e.message}")
        }

        // 重新启动HTTP服务器
        try {
            if (mHttpServer == null) {
                mHttpServer = embeddedServer(Netty, port = Constants.Port.HTTP_SERVER) {
                    configureKtorServer(this@NetworkService)
                }
                mHttpServer?.start(wait = false)
                Timber.d("HTTP Server restarted on port ${Constants.Port.HTTP_SERVER}")

                // 发送日志事件
                EventBus.getDefault().post(LogEvent(LogEntry(message = "HTTP服务器已启动 (端口: ${Constants.Port.HTTP_SERVER})", type = LogType.SUCCESS)))
            } else {
                Timber.d("HTTP Server already running")
            }
        } catch (e: Exception) {
            Timber.e("Error starting HTTP server: ${e.message}")
            EventBus.getDefault().post(LogEvent(LogEntry(message = "HTTP服务器启动失败: ${e.message}", type = LogType.ERROR)))
        }

        // 重新初始化并启动设备发现
        try {
            DeviceDiscoverManager.getInstance().onDeviceDiscover {
                Timber.d("Device: ip => ${it.ip}, name => ${it.name}, platform => ${it.platform}")
            }
            DeviceDiscoverManager.getInstance().startDiscover()
            Timber.d("DeviceDiscoverManager restarted")
            EventBus.getDefault().post(LogEvent(LogEntry(message = "广播服务已启动", type = LogType.SUCCESS)))
        } catch (e: Exception) {
            Timber.e("Error starting device discovery: ${e.message}")
            EventBus.getDefault().post(LogEvent(LogEntry(message = "广播服务启动失败: ${e.message}", type = LogType.ERROR)))
        }

        // 重新启动热点检查定时器
        startHotspotCheckTimer()

        Timber.d("AirController services enabled successfully")
        EventBus.getDefault().post(LogEvent(LogEntry(message = "所有服务已成功启动", type = LogType.SUCCESS)))
    }

    private fun registerEventBus() {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    private fun unRegisterEventBus() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        mHttpServer?.stop(1000, 2000)
        mWakeLock.release()
        unRegisterEventBus()
        unregisterNetworkListener()
        stopHotspotCheckTimer()
        try {
            unregisterReceiver(mToggleReceiver)
        } catch (e: Exception) {
            Timber.e("Error unregistering toggle receiver: ${e.message}")
        }
        stopForeground(true)
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, resources.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        // Main intent to open app
        val nfIntent = Intent(this, MainActivity::class.java)
        nfIntent.action = Intent.ACTION_MAIN
        nfIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, RC_NOTIFICATION, nfIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, RC_NOTIFICATION, nfIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        // Toggle service intent
        val toggleIntent = Intent(ACTION_TOGGLE_SERVICE)
        val togglePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getBroadcast(this, RC_TOGGLE, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getBroadcast(this, RC_TOGGLE, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        // Create custom notification layout - 使用扩展布局
        val customView = RemoteViews(packageName, R.layout.notification_custom_expanded)

        // 获取网络状态
        val networkStatus = when (NetworkUtil.getNetworkStatus(this)) {
            "WiFi" -> "WiFi"
            "Hotspot" -> "热点"
            "WiFi+Hotspot" -> "WiFi+热点" // 注意：没有空格
            "No Network" -> "无网络"
            else -> NetworkUtil.getNetworkStatus(this) // 直接使用原始值
        }
        val wifiIp = NetworkUtil.getWifiIpAddress(this)
        val hotspotIp = NetworkUtil.getHotspotIpAddress(this)
        val currentIp = wifiIp ?: hotspotIp

        // 设置状态文本
        val statusText = when {
            !isAirControllerEnabled -> getString(R.string.service_stopped)
            isDeviceConnected && connectedDevice != null -> "已配对: ${connectedDevice?.name}"
            else -> getString(R.string.waiting_for_pairing)
        }

        // 设置网络信息
        val networkText = when {
            !isAirControllerEnabled -> ""
            isDeviceConnected && connectedDevice != null -> "电脑: ${connectedDevice?.ip}"
            networkStatus == "无网络" -> "网络: 未连接"
            else -> "网络: $networkStatus"
        }

        // 设置IP信息
        val ipText = when {
            !isAirControllerEnabled -> ""
            isDeviceConnected && connectedDevice != null -> {
                // 已连接时显示本机IP
                if (currentIp != null) "本机: $currentIp" else ""
            }
            currentIp != null -> "IP: $currentIp"
            else -> ""
        }

        val toggleText = if (isToggleInProgress) {
            getString(R.string.processing)
        } else if (isAirControllerEnabled) {
            getString(R.string.disable)
        } else {
            getString(R.string.enable)
        }

        // Set texts
        customView.setTextViewText(R.id.notification_title, getString(R.string.app_name))
        customView.setTextViewText(R.id.notification_status, statusText)
        customView.setTextViewText(R.id.notification_network, networkText)
        customView.setTextViewText(R.id.notification_ip, ipText)
        customView.setTextViewText(R.id.notification_toggle_button, toggleText)

        // 设置网络和IP文本的可见性
        customView.setViewVisibility(R.id.notification_network, if (networkText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        customView.setViewVisibility(R.id.notification_ip, if (ipText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)

        // Set button background based on state
        val buttonBackground = when {
            isToggleInProgress -> R.drawable.notification_button_gray
            isAirControllerEnabled -> R.drawable.notification_button_red
            else -> R.drawable.notification_button_green
        }
        customView.setInt(R.id.notification_toggle_button, "setBackgroundResource", buttonBackground)

        // Set button click
        if (!isToggleInProgress) {
            customView.setOnClickPendingIntent(R.id.notification_toggle_button, togglePendingIntent)
        }

        builder.setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setCustomContentView(customView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setStyle(Notification.DecoratedCustomViewStyle())
        }

        return builder.build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun toggleService() {
        if (isToggleInProgress) {
            Timber.d("Toggle already in progress, ignoring")
            return
        }

        isToggleInProgress = true
        updateNotification() // 立即更新通知显示"处理中"

        isAirControllerEnabled = !isAirControllerEnabled
        saveServiceState(isAirControllerEnabled)

        // 通知MainActivity更新UI
        EventBus.getDefault().post(AirControllerStateEvent(isAirControllerEnabled))
        // The actual enable/disable will be handled by onAirControllerStateChanged
    }

    private fun saveServiceState(enabled: Boolean) {
        val prefs = getSharedPreferences("AirControllerPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_enabled", enabled).apply()
    }

    private fun registerNetworkListener() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // 网络可用时更新通知
                android.os.Handler(mainLooper).postDelayed({
                    updateNotification()
                }, 500) // 延迟一下等待网络完全建立
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                // 网络丢失时更新通知
                android.os.Handler(mainLooper).post {
                    updateNotification()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                // 网络能力变化时更新通知（如WiFi切换到热点）
                android.os.Handler(mainLooper).post {
                    updateNotification()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        }
    }

    private fun unregisterNetworkListener() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Timber.e("Error unregistering network callback: ${e.message}")
            }
        }
    }

    private fun startHotspotCheckTimer() {
        hotspotCheckTimer?.cancel()
        hotspotCheckTimer = java.util.Timer()
        hotspotCheckTimer?.scheduleAtFixedRate(
            object : java.util.TimerTask() {
                override fun run() {
                    // 定期检查热点状态变化
                    android.os.Handler(mainLooper).post {
                        updateNotification()
                    }
                }
            },
            0, 3000 // 每3秒检查一次
        )
    }

    private fun stopHotspotCheckTimer() {
        hotspotCheckTimer?.cancel()
        hotspotCheckTimer = null
    }
}
