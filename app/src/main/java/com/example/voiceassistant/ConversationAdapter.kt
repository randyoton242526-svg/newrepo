package com.example.voiceassistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ConversationAdapter : RecyclerView.Adapter<ConversationAdapter.MessageViewHolder>() {

    enum class MessageType { USER, ASSISTANT, SYSTEM }

    data class Message(
        val text: String,
        val type: MessageType,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val messages = mutableListOf<Message>()

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount() = messages.size
    override fun getItemViewType(position: Int) = messages[position].type.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = when (viewType) {
            MessageType.USER.ordinal      -> R.layout.item_message_user
            MessageType.ASSISTANT.ordinal -> R.layout.item_message_assistant
            else                          -> R.layout.item_message_system
        }
        return MessageViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) =
        holder.bind(messages[position])

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.tv_message)
        fun bind(m: Message) { tv.text = m.text }
    }
}
