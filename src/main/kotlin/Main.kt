import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import listeners.ChatServiceListener
import models.ChatResponse
import models.Message
import models.FetchMessagesResponse
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import utils.ChatEndpointCaller
import utils.ChatEndpointCallerWithData
import utils.ResponseCallback
import java.io.IOException

fun main() {
    println("hello, kotlin")

    ChatServiceManager.Builder<ChatMessage>()
        .setSocketURL("")
        .setUsername("")
        .setMissingMessagesCaller(GetMissingMessagesUserCase())
        .setMessageAckCaller(AcknowledgeMessagesUseCase())
        .setExpectedReceivers(listOf())
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
    val message: String?,
    val timestamp: String?,
    val messageReference: String?,
    val chatReference: String?,
    val text: String?,
    val sender: String?,
    val receiver: String?,
    ): Message(
    _messageId = messageReference, _timestamp = timestamp, _message = text,
    _sender = sender, _receiver = receiver
)
@Serializable
data class ChatHistoryResponse(
    val data: List<ChatMessage>,
    val isSuccessful: Boolean?,
    val error: String,
): FetchMessagesResponse<ChatMessage>(data, isSuccessful, error)

@Serializable
data class AckResponse(
    val data: String?,
    val isSuccessful: Boolean?,
    val error: String?,
): ChatResponse(data, isSuccessful, error)

class GetMissingMessagesUserCase: ChatEndpointCaller<ChatHistoryResponse> {

    private val client: OkHttpClient = OkHttpClient()

    override suspend fun call(handler: ResponseCallback<ChatHistoryResponse>) {
        client.newCall(Request.Builder().url("").build())
            .enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    handler.onFailure(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    handler.onResponse(Json.decodeFromString(ChatHistoryResponse.serializer(), response.body.toString()))
                }
            })
    }
}

class AcknowledgeMessagesUseCase: ChatEndpointCallerWithData<List<ChatMessage>, AckResponse> {

    private val client: OkHttpClient = OkHttpClient()

    override suspend fun call(data: List<ChatMessage>?, handler: ResponseCallback<AckResponse>) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        data?.let { messages ->
            val sortedMessages = messages.sortedBy { it.timestamp }
            val requestBody = getAckRequestBuilder(
                from = sortedMessages.first(), to = sortedMessages.last()).toRequestBody(mediaType)
            client.newCall(Request.Builder().url("")
                .post(requestBody).build())
                .enqueue(object: Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        handler.onFailure(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        handler.onResponse(Json.decodeFromString(AckResponse.serializer(), response.body.toString()))
                    }
                })
        }
    }

    private fun getAckRequestBuilder(from: ChatMessage, to: ChatMessage): String {
        return """
                {
                    "username":${from.sender},
                    "chatReference":${from.chatReference},
                    "from":${from.timestamp},
                    "to":${to.timestamp}
                }
            """.trimIndent()
    }
}