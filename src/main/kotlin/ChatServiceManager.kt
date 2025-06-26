import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import listeners.ChatServiceListener
import models.Message
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.IOException
import java.io.Serializable
import java.util.PriorityQueue

class ChatServiceManager<M: Message>
private constructor(private val serializer: KSerializer<M>) : IChatServiceManager<M> {

    private var messageQueue = PriorityQueue<String>()
    var socketURL: String? = null
    private set

    var chatHistoryURL: String? = null
    private set

    var messageAckURL: String? = null
    private set

    private var chatServiceListener: ChatServiceListener<M>? = null

    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    override fun connect() {
        client.dispatcher.executorService.shutdown()
    }

    override fun disconnect() {
        socket?.close(1000, "end session")
    }

    override fun fetchMissingMessages() {
        chatHistoryURL?.let {
            client.newCall(Request.Builder().url(it).build())
                .enqueue(fetchRemoteChatHistoryResponseListener())
        }
    }

    override fun acknowledgeMessagesInRange(serializedAckRequest: String) {
        messageAckURL?.let {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = serializedAckRequest.toRequestBody(mediaType)
            client.newCall(Request.Builder().url(it)
                .post(requestBody).build())
                .enqueue(acknowledgeMessagesResponseListener())
        }
    }

    override fun sendMessage(message: M) {
        chatServiceListener?.onSend(message)
        socket?.send(Json.encodeToString(serializer, message))
    }

    private fun acknowledgeMessagesResponseListener(): Callback {
        return object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                chatServiceListener?.onError(
                    ChatServiceError.ACKNOWLEDGE_FAILED, e.message ?: ""
                )
            }

            override fun onResponse(call: Call, response: Response) {

            }
        }
    }

    private fun fetchRemoteChatHistoryResponseListener(): Callback {
        return object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                chatServiceListener?.onError(
                    ChatServiceError.FETCH_REMOTE_FAILED, e.message ?: ""
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (response.isSuccessful) {
                        res.body?.let { body ->
                            val messages = Json.decodeFromString<List<M>>(body.toString())
                            chatServiceListener?.onMissingMessagesFetched(messages)
                        }
                    }
                }
            }
        }
    }

    private fun webSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                // call API to acknowledge message
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                // call API to acknowledge message
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                fetchRemoteChatHistoryResponseListener()
            }
        }
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

        fun build(serializer: KSerializer<M>): ChatServiceManager<M> {
            return ChatServiceManager(serializer).apply {
                socketURL = this@Builder.socketURL
                chatHistoryURL = this@Builder.chatHistoryURL
                messageAckURL = this@Builder.messageAckURL
                chatServiceListener = this@Builder.chatServiceListener
                socketURL?.let {
                    socket = client.newWebSocket(Request.Builder().url(it).build(), webSocketListener())
                }
            }
        }
    }
}