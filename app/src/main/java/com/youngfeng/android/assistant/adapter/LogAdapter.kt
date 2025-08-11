package com.youngfeng.android.assistant.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.youngfeng.android.assistant.R
import com.youngfeng.android.assistant.model.LogEntry
import com.youngfeng.android.assistant.model.LogType

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private var logs: List<LogEntry> = emptyList()

    fun submitList(newLogs: List<LogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount() = logs.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.text_timestamp)
        private val messageText: TextView = itemView.findViewById(R.id.text_message)

        fun bind(logEntry: LogEntry) {
            timestampText.text = logEntry.timestamp
            messageText.text = logEntry.message

            // Set color based on log type
            val color = when (logEntry.type) {
                LogType.SUCCESS -> Color.parseColor("#2ECC71")
                LogType.WARNING -> Color.parseColor("#F39C12")
                LogType.ERROR -> Color.parseColor("#E74C3C")
                LogType.NETWORK -> Color.parseColor("#3498DB")
                LogType.FILE -> Color.parseColor("#9B59B6")
                LogType.API -> Color.parseColor("#1ABC9C")
                LogType.INFO -> Color.parseColor("#666666")
            }
            messageText.setTextColor(color)
        }
    }
}
