package com.youngfeng.android.assistant.manager

import com.youngfeng.android.assistant.model.LogEntry
import com.youngfeng.android.assistant.model.LogType
import org.greenrobot.eventbus.EventBus

data class LogEvent(val logEntry: LogEntry)

object LogManager {
    fun log(message: String, type: LogType = LogType.INFO) {
        val logEntry = LogEntry(message = message, type = type)
        EventBus.getDefault().post(LogEvent(logEntry))
    }
}
