import kotlinx.serialization.Serializable
import models.Message

fun main() {
    println("hello, kotlin")

}

@Serializable
class ChatMessage(
    val message: String,
    val timestamp: String,
    val messageReference: String,
    val text: String,
    ): Message(messageId = messageReference, messageTimestamp = timestamp)