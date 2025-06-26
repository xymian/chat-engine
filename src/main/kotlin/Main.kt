import kotlinx.serialization.Serializable
import listeners.ChatServiceListener
import models.Message

fun main() {
    println("hello, kotlin")

    ChatServiceManager.Builder<ChatMessage>()
        .setSocketURL("")
        .setChatDatabaseURL("")
        .setUsername("")
        .setExpectedReceiver("")
        .setMessageAckURL("")
        .setAckRequestBuilder { receivedMessage ->
            getAckRequestBuilder(receivedMessage)
        }
        .setChatServiceListener(chatServiceLister)
        .build(ChatMessage.serializer())

}

val chatServiceLister = object : ChatServiceListener<ChatMessage> {
    override fun onConnect() {
        TODO("Not yet implemented")
    }

    override fun onDisconnect() {
        TODO("Not yet implemented")
    }

    override fun onSend(message: ChatMessage) {
        TODO("Not yet implemented")
    }

    override fun onReceive(message: ChatMessage) {
        TODO("Not yet implemented")
    }

    override fun onReceive(messages: List<ChatMessage>) {
        TODO("Not yet implemented")
    }

    override fun onMissingMessagesFetched(messages: List<ChatMessage>) {
        TODO("Not yet implemented")
    }

    override fun onError(error: ChatServiceError, message: String) {
        TODO("Not yet implemented")
    }
}

fun getAckRequestBuilder(receivedMessage: ChatMessage): String {
    return """
                {
                    "username":${receivedMessage.receiver},
                    "chatReference":${receivedMessage.chatReference},
                    "from":${receivedMessage.timestamp},
                    "to":${receivedMessage.timestamp}
                }
            """.trimIndent()
}

@Serializable
class ChatMessage(
    val message: String,
    val timestamp: String,
    val messageReference: String,
    val chatReference: String,
    val text: String,
    val sender: String,
    val receiver: String,
    ): Message(
    _messageId = messageReference, _timestamp = timestamp, _message = text,
    _sender = sender, _receiver = receiver
)