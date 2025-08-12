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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.ON_AFTER_RELEASE
import android.os.PowerManager.PARTIAL_WAKE_LOCK
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

    private var mHttpServer: ApplicationEngine? = null

    private lateinit var mWakeLock: PowerManager.WakeLock
    private lateinit var heartbeatServer: HeartbeatServerPlus
    private val mGson by lazy { Gson() }

    private companion object {
        const val DEFAULT_TIMEOUT = 10
        const val RC_NOTIFICATION = 0x1001
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()

        mWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            ON_AFTER_RELEASE or PARTIAL_WAKE_LOCK,
            "AirController:NetworkService"
        )
        mWakeLock.acquire(60 * 60 * 1000L)

        // 初始化IP白名单（清空旧记录，默认启用）
        IpWhitelistManager.clear()
        Timber.d("IP whitelist initialized")

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

        registerEventBus()
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
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onAirControllerStateChanged(event: AirControllerStateEvent) {
        if (event.enabled) {
            // 启用AirController - 重新启动所有服务
            enableServices()
        } else {
            // 禁用AirController - 停止所有服务
            disableServices()
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
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "channelId" + System.currentTimeMillis()
            val channel = NotificationChannel(channelId, resources.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)

            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
        val nfIntent = Intent(this, MainActivity::class.java)
        nfIntent.action = Intent.ACTION_MAIN
        nfIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, RC_NOTIFICATION, nfIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, RC_NOTIFICATION, nfIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        builder.setContentIntent(pendingIntent)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText(getString(R.string.working))
            .setWhen(System.currentTimeMillis())
            .setOngoing(false)

        val notification = builder.build()
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
        stopForeground(true)
        super.onDestroy()
    }
}
