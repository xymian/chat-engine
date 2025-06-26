import models.Message
import java.io.Serializable

interface IChatServiceManager<M: Message> {
    fun connect()
    fun disconnect()
    fun fetchMissingMessages()
    fun sendMessage(message: M)
    fun acknowledgeMessagesInRange(serializedAckRequest: String)
}