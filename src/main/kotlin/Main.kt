import kotlinx.serialization.Serializable
import listeners.ChatServiceListener
import models.Message

fun main() {
    println("hello, kotlin")

    ChatServiceManager.Builder<ChatMessage>()
        .setSocketURL("")
        .setChatDatabaseURL("")
        .setMessageAckURL("")
        .setChatServiceListener(object : ChatServiceListener<ChatMessage> {
            override fun onSend(message: ChatMessage) {
                TODO("Not yet implemented")
            }

            override fun onReceive(message: ChatMessage) {
                TODO("Not yet implemented")
            }

            override fun onDisconnect() {
                TODO("Not yet implemented")
            }

            override fun onConnect() {
                TODO("Not yet implemented")
            }

        }).build()

}

@Serializable
class ChatMessage(
    val message: String,
    val timestamp: String,
    val messageReference: String,
    val text: String,
    ): Message(messageId = messageReference, messageTimestamp = timestamp)