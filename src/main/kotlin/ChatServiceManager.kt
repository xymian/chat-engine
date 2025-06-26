import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import listeners.ChatServiceListener
import models.Message
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.PriorityQueue

class ChatServiceManager<M: Message>
private constructor(private val serializer: KSerializer<M>) : IChatServiceManager<M> {

    private var messageQueue = PriorityQueue<M>()
    private var stagingMessages = mutableListOf<M>()

    private var shouldExposeReceivedSocketMessages = true
    private var shouldGoToStaging = false
    private var isSocketConnected = false

    private var socketURL: String? = null
    private var chatHistoryURL: String? = null
    private var messageAckURL: String? = null

    private var chatServiceListener: ChatServiceListener<M>? = null

    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    private var me: String? = null
    private var receivers: List<String> = listOf()

    private var ackRequestBuilder: ((M) -> String)? = null

    override fun connect() {
        client.dispatcher.executorService.shutdown()
    }

    override fun disconnect() {
        socket?.close(1000, "end session")
    }

    private fun fetchMissingMessages() {
        chatHistoryURL?.let {
            client.newCall(Request.Builder().url(it).build())
                .enqueue(fetchRemoteChatHistoryResponseListener())
        }
    }

    private fun acknowledgeMessagesInRange(serializedAckRequest: String) {
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
                            if (!isSocketConnected) {
                                connect()
                                fetchMissingMessages()
                            } else {
                                emptyMessageQueueAndExposeMessages()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun emptyMessageQueueAndExposeMessages() {
        shouldGoToStaging = true
        val messages = mutableListOf<M>()
        while (messageQueue.isNotEmpty()) {
            messages.add(messageQueue.poll())
        }
        messages.addAll(stagingMessages)
        stagingMessages = mutableListOf()
        chatServiceListener?.onReceive(messages.sortedBy { it._timestamp })
        shouldExposeReceivedSocketMessages = true
    }

    private fun isAValidMessageReceiver(userId: String): Boolean {
        return if (userId != me) {
            receivers.contains(userId)
        } else {
            false
        }
    }

    private fun webSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isSocketConnected = false
                shouldExposeReceivedSocketMessages = false
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isSocketConnected = false
                shouldExposeReceivedSocketMessages = false
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.decodeFromString(serializer, text)

                if (isAValidMessageReceiver(message._sender)) {
                    ackRequestBuilder?.invoke(message)?.let { request ->
                        acknowledgeMessagesInRange(request)
                    }
                }

                if (shouldExposeReceivedSocketMessages) {
                    if (isAValidMessageReceiver(message._sender)) {
                        if (stagingMessages.isNotEmpty()) {
                            chatServiceListener?.onReceive(
                                stagingMessages.apply { add(message) }.sortedBy { it._timestamp }
                            )
                            stagingMessages = mutableListOf()
                        }
                        chatServiceListener?.onReceive(message)
                    } else {
                        if (message._sender == me) {
                            chatServiceListener?.onSend(message)
                        } else {
                            chatServiceListener?.onError(
                                ChatServiceError.MESSAGE_LEAK_ERROR, "unknown message sender ${message._sender}"
                            )
                        }
                    }
                } else {
                    if (shouldGoToStaging) {
                        if (isAValidMessageReceiver(message._sender)) {
                            stagingMessages.add(message)
                        }
                    } else {
                        if (isAValidMessageReceiver(message._sender)) {
                            messageQueue.add(message)
                        }
                    }
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                isSocketConnected = true
                fetchMissingMessages()
            }
        }
    }

    class Builder<M: Message> {
        private var socketURL: String? = null
        private var chatHistoryURL: String? = null
        private var messageAckURL: String? = null

        private var chatServiceListener: ChatServiceListener<M>? = null

        private var me: String? = null
        private var receivers: List<String> = listOf()

        private var ackRequestBuilder: ((M) -> String)? = null

        fun setAckRequestBuilder(builder: (M) -> String): Builder<M> {
            ackRequestBuilder = builder
            return this
        }
        fun setUsername(userId: String): Builder<M> {
            me = userId
            return this
        }

        fun setExpectedReceiver(userId: String): Builder<M> {
            receivers = listOf(userId)
            return this
        }

        fun setExpectedReceivers(userIds: List<String>): Builder<M> {
            receivers = userIds
            return this
        }

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
                this.me = this@Builder.me
                this.receivers = this@Builder.receivers
                this.ackRequestBuilder = this@Builder.ackRequestBuilder
            }
        }
    }
}