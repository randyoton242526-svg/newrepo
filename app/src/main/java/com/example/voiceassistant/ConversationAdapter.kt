package com.example.voiceassistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * ConversationAdapter
 * -------------------
 * A simple RecyclerView adapter for the conversation log.
 * Each item is either a USER message or an ASSISTANT message;
 * they get different background tints via the item layout.
 *
 * Thread-safety: [addMessage] should be called only from the main thread
 * (use Handler/Dispatchers.Main before calling from coroutines).
 */
class ConversationAdapter : RecyclerView.Adapter<ConversationAdapter.MessageViewHolder>() {

    // ---- Data model -------------------------------------------------------

    enum class Role { USER, ASSISTANT, SYSTEM }

    data class Message(
        val role: Role,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val messages = mutableListOf<Message>()

    // ---- Public API -------------------------------------------------------

    /** Append a new message and notify the adapter. */
    fun addMessage(role: Role, text: String) {
        messages.add(Message(role, text))
        notifyItemInserted(messages.size - 1)
    }

    /** Clear all messages. */
    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    // ---- RecyclerView.Adapter overrides -----------------------------------

    override fun getItemCount(): Int = messages.size

    override fun getItemViewType(position: Int): Int =
        messages[position].role.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = when (viewType) {
            Role.USER.ordinal      -> R.layout.item_message_user
            Role.ASSISTANT.ordinal -> R.layout.item_message_assistant
            else                   -> R.layout.item_message_system
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    // ---- ViewHolder -------------------------------------------------------

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.text
        }
    }
}
