package com.youngfeng.android.assistant.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val message: String,
    val type: LogType = LogType.INFO
)

enum class LogType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    NETWORK,
    FILE,
    API
}
