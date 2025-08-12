package com.youngfeng.android.assistant.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.youngfeng.android.assistant.R

class IpWhitelistAdapter(
    private val onRemoveClick: (String) -> Unit
) : ListAdapter<String, IpWhitelistAdapter.IpViewHolder>(IpDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IpViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ip_whitelist, parent, false)
        return IpViewHolder(view)
    }

    override fun onBindViewHolder(holder: IpViewHolder, position: Int) {
        val ip = getItem(position)
        holder.bind(ip, onRemoveClick)
    }

    class IpViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ipTextView: TextView = itemView.findViewById(R.id.text_ip_address)
        private val removeButton: ImageView = itemView.findViewById(R.id.btn_remove_ip)

        fun bind(ip: String, onRemoveClick: (String) -> Unit) {
            ipTextView.text = ip
            removeButton.setOnClickListener {
                onRemoveClick(ip)
            }
        }
    }

    class IpDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}