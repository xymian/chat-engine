import listeners.ChatServiceListener
import models.Message

class ChatServiceManager<M: Message> private constructor() : IChatServiceManager<M> {
    var socketURL: String? = null
    private set

    var chatHistoryURL: String? = null
    private set

    var messageAckURL: String? = null
    private set

    private var chatServiceListener: ChatServiceListener<M>? = null

    override fun connect() {
        TODO("Not yet implemented")
    }

    override fun disconnect() {
        TODO("Not yet implemented")
    }

    override fun updateLocalChatHistory(): List<M> {
        TODO("Not yet implemented")
    }

    override fun acknowledgeMessagesInRange(timestampFrom: String, timestampTo: String) {
        TODO("Not yet implemented")
    }

    override fun acknowledgeMessage(message: M) {
        TODO("Not yet implemented")
    }

    override fun sendMessage(message: M) {
        TODO("Not yet implemented")
    }

    class Builder<M: Message> {
        private var socketURL: String? = null

        private var chatHistoryURL: String? = null

        private var messageAckURL: String? = null

        private var chatServiceListener: ChatServiceListener<M>? = null

        fun setChatServiceListener(listener: ChatServiceListener<M>): Builder<M> {
            chatServiceListener = listener
            return this
        }

        fun setSocketURL(url: String): Builder<M> {
            socketURL = url
            return this
        }

        fun setChatDatabaseURL(url: String): Builder<M> {
            chatHistoryURL = url
            return this
        }

        fun setMessageAckURL(url: String): Builder<M> {
            messageAckURL = url
            return this
        }

        fun build(): ChatServiceManager<M> {
            return ChatServiceManager<M>().apply {
                socketURL = this@Builder.socketURL
                chatHistoryURL = this@Builder.chatHistoryURL
                messageAckURL = this@Builder.messageAckURL
                chatServiceListener = this@Builder.chatServiceListener
            }
        }
    }
}