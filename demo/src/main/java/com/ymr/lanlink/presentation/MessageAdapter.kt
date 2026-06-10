package com.ymr.lanlink.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ymr.lanlink.core.domain.model.LanMessage

class MessageAdapter : ListAdapter<LanMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(message: LanMessage) {
            textView.text = message.payload
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<LanMessage>() {
        override fun areItemsTheSame(oldItem: LanMessage, newItem: LanMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LanMessage, newItem: LanMessage): Boolean {
            return oldItem == newItem
        }
    }
}