import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import listeners.ChatServiceListener
import models.FetchMessagesResponse
import models.Message
import okhttp3.*
import utils.*
import java.lang.Exception
import java.util.PriorityQueue

class ChatServiceManager<M: Message, R: FetchMessagesResponse<M>>
private constructor(private val serializer: KSerializer<M>) : IChatServiceManager<M> {

    private var messageQueue = PriorityQueue<M>()

    private var exposeSocketMessages = true
    private var isSocketConnected = false

    private var missingMessagesCaller: ChatEndpointCaller? = null
    private var messageAckCaller: ChatEndpointCaller? = null
    private var socketURL: String? = null

    private var me: String? = null
    private var receivers: List<String> = listOf()

    private var chatServiceListener: ChatServiceListener<M>? = null

    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var localStorageInstance: ILocalStorage<M>? = null

    private val mutex = Mutex()

    private var delay = 1_000L
    private val maxDelay = 16_000L

    override fun connect() {
        runInBackground {
            fetchMissingMessages()
        }
    }

    private fun startSocket() {
        socketURL?.let {
            if (!isSocketConnected) {
                socket = client.newWebSocket(Request.Builder().url(it).build(), webSocketListener())
            }
        }
    }

    override fun disconnect() {
        socket?.close(1000, "end session")
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun fetchMissingMessages() {
        missingMessagesCaller?.call(data = null, object: ChatEndpointCaller.ResponseCallback<R> {
            override fun onResponse(response: R) {
                runOnMainThread {
                    onMissingMessagesFetched(response)
                }
            }

            override fun onFailure(e: Exception?) {
                Napier.e("error: ${e?.message}")
                runOnMainThread {
                    chatServiceListener?.onError(
                        ChatServiceError.FETCH_REMOTE_FAILED, e?.message ?: "unknown error"
                    )
                    scheduleMissingMessageRetry()
                }
            }
        })
        /*chatHistoryURL?.let {
            client.newCall(Request.Builder().url(it).build())
                .enqueue(fetchRemoteChatHistoryResponseListener())
        }*/
    }

    private suspend fun acknowledgeMessages(messages: List<M>) {
        messageAckCaller?.call(data = messages, object: ChatEndpointCaller.ResponseCallback<Any> {
            override fun onResponse(response: Any) {

            }
            override fun onFailure(e: Exception?) {
                Napier.e("error: ${e?.message}")
                chatServiceListener?.onError(
                    ChatServiceError.ACKNOWLEDGE_FAILED, e?.message ?: ""
                )
            }
        })
        /*messageAckURL?.let {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = serializedAckRequest.toRequestBody(mediaType)
            client.newCall(Request.Builder().url(it)
                .post(requestBody).build())
                .enqueue(acknowledgeMessagesResponseListener())
        }*/
    }

    override fun sendMessage(message: M) {
        chatServiceListener?.onSend(message)
        socket?.send(Json.encodeToString(serializer, message))
    }

    private fun onMissingMessagesFetched(response: R) {
        if (response._isSuccessful) {
            response._data?.let { messages ->
                if (messages.isNotEmpty()) {
                    runInBackground {
                        acknowledgeMessages(messages)
                    }
                }
                runInBackground {
                    localStorageInstance?.store(messages)
                }
                if (exposeSocketMessages) {
                    chatServiceListener?.onReceive(messages)
                } else {
                    runLockingTask(mutex) {
                        messageQueue.addAll(messages)
                    }
                }
                if (!isSocketConnected) {
                    exposeSocketMessages = false
                    scheduleSocketReconnect()
                } else {
                    runLockingTask(mutex) {
                        if (messageQueue.isNotEmpty()) {
                            emptyMessageQueue {
                                runOnMainThread {
                                    chatServiceListener?.onReceive(it.sortedBy { it._timestamp })
                                }
                            }
                        } else {
                            runOnMainThread {
                                chatServiceListener?.onReceive(messages)
                            }
                        }
                        exposeSocketMessages = true
                    }
                }
            }
        } else {
            Napier.e("server error: ${response._error?.message}")
        }
    }

    private fun scheduleSocketReconnect() {
        runInBackground {
            delay(delay)
            delay = (delay * 2).coerceAtMost(maxDelay)
            startSocket()
        }
    }

    private fun scheduleMissingMessageRetry() {
        runInBackground {
            delay = (delay * 2).coerceAtMost(maxDelay)
            fetchMissingMessages()
        }
    }

    private fun emptyMessageQueue(exposeMessages: (List<M>) -> Unit) {
        val messages = mutableListOf<M>()
        while (messageQueue.isNotEmpty()) {
            messages.add(messageQueue.poll())
        }
        exposeMessages(messages.distinctBy { it._messageId })
    }

    private fun isSenderPartOfThisChatAndIsntMe(userId: String): Boolean {
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
                exposeSocketMessages = false
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isSocketConnected = false
                exposeSocketMessages = false
                runInBackground {
                    fetchMissingMessages()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.decodeFromString(serializer, text)
                if (isSenderPartOfThisChatAndIsntMe(message._sender)) {
                    runInBackground {
                        acknowledgeMessages(listOf(message))
                    }
                }
                runInBackground {
                    localStorageInstance?.store(message)
                }
                if (exposeSocketMessages) {
                    if (isSenderPartOfThisChatAndIsntMe(message._sender)) {
                        runLockingTask(mutex) {
                            messageQueue.add(message)
                            if (messageQueue.isNotEmpty()) {
                                emptyMessageQueue {
                                    runOnMainThread {
                                        chatServiceListener?.onReceive(it.sortedBy { it._timestamp })
                                    }
                                }
                            } else {
                                runOnMainThread {
                                    chatServiceListener?.onReceive(message)
                                }
                            }
                        }
                    } else {
                        if (message._sender == me) {
                            chatServiceListener?.onSend(message)
                        } else {
                            chatServiceListener?.onError(
                                ChatServiceError.MESSAGE_LEAK_ERROR, "unknown message sender ${message._sender}"
                            )
                            disconnect()
                        }
                    }
                } else {
                    if (isSenderPartOfThisChatAndIsntMe(message._sender)) {
                        runLockingTask(mutex) {
                            messageQueue.add(message)
                        }
                    } else {
                        if (message._sender == me) {
                            chatServiceListener?.onSend(message)
                        } else {
                            chatServiceListener?.onError(
                                ChatServiceError.MESSAGE_LEAK_ERROR,
                                "unknown message sender ${message._sender}"
                            )
                        }
                    }
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                isSocketConnected = true
                chatServiceListener?.onConnect()
                runInBackground {
                    fetchMissingMessages()
                }
            }
        }
    }

    class Builder<M: Message, R: FetchMessagesResponse<M>> {
        private var socketURL: String? = null

        private var chatServiceListener: ChatServiceListener<M>? = null

        private var me: String? = null
        private var receivers: List<String> = listOf()

        private var missingMessagesCaller: ChatEndpointCaller? = null
        private var messageAckCaller: ChatEndpointCaller? = null

        fun setMessageAckCaller(caller: ChatEndpointCaller): Builder<M, R> {
            messageAckCaller = caller
            return this
        }

        fun setMissingMessagesCaller(caller: ChatEndpointCaller): Builder<M, R> {
            missingMessagesCaller = caller
            return this
        }

        fun setUsername(userId: String): Builder<M, R> {
            me = userId
            return this
        }

        fun setExpectedReceivers(userIds: List<String>): Builder<M, R> {
            receivers = userIds
            return this
        }

        fun setChatServiceListener(listener: ChatServiceListener<M>): Builder<M, R> {
            chatServiceListener = listener
            return this
        }

        fun setSocketURL(url: String): Builder<M, R> {
            socketURL = url
            return this
        }

        fun build(serializer: KSerializer<M>): ChatServiceManager<M, FetchMessagesResponse<M>> {
            return ChatServiceManager(serializer).apply {
                socketURL = this@Builder.socketURL
                chatServiceListener = this@Builder.chatServiceListener
                socketURL?.let {
                    socket = client.newWebSocket(Request.Builder().url(it).build(), webSocketListener())
                }
                this.me = this@Builder.me
                this.receivers = this@Builder.receivers
                this.missingMessagesCaller = this@Builder.missingMessagesCaller
                this.messageAckCaller = this@Builder.messageAckCaller
            }
        }
    }
}