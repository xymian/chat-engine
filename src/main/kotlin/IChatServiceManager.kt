import listeners.ChatServiceListener
import models.Message

interface IChatServiceManager<M: Message> {
    fun connect()
    fun disconnect()
    fun updateLocalChatHistory(): List<M>
    fun sendMessage(message: M)
    fun acknowledgeMessage(message: M)
    fun acknowledgeMessagesInRange(timestampFrom: String, timestampTo: String)
}