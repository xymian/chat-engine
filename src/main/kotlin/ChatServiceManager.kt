import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import listeners.ChatServiceListener
import models.ChatResponse
import models.FetchMessagesResponse
import models.Message
import okhttp3.*
import utils.*
import java.lang.Exception
import java.util.PriorityQueue

class ChatServiceManager<M: Message>
private constructor(private val serializer: KSerializer<M>) : IChatServiceManager<M> {

    private var messageQueue = PriorityQueue<M>()
    private var ackMessages = mutableListOf<M>()

    private var exposeSocketMessages = true
    private var isSocketConnected = false

    private var missingMessagesCaller: ChatEndpointCaller<M, FetchMessagesResponse<M>>? = null
    private var messageAckCaller: ChatEndpointCaller<List<M>, ChatResponse>? = null

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
        missingMessagesCaller?.call(
            data = null, handler = object: ChatEndpointCaller.ResponseCallback<FetchMessagesResponse<M>> {
            override fun onResponse(response: FetchMessagesResponse<M>) {
                runOnMainThread {
                    onMissingMessagesFetched(response)
                }
            }

            override fun onFailure(e: Exception?) {
                Napier.e("error: ${e?.message}")
                chatServiceListener?.onError(
                    ChatServiceError.ACKNOWLEDGE_FAILED, e?.message ?: ""
                )
            }
        })
    }

    private suspend fun acknowledgeMessages(messages: List<M>) {
        messageAckCaller?.call(data = messages, handler = object: ChatEndpointCaller.ResponseCallback<ChatResponse> {
            override fun onResponse(response: ChatResponse) {
                runInBackground {
                    fetchMissingMessages()
                }
            }

            override fun onFailure(e: Exception?) {
                Napier.e("error: ${e?.message}")
                chatServiceListener?.onError(
                    ChatServiceError.ACKNOWLEDGE_FAILED, e?.message ?: ""
                )
                runInBackground {
                    fetchMissingMessages()
                }
            }
        })
    }

    override fun sendMessage(message: M) {
        chatServiceListener?.onSend(message)
        socket?.send(Json.encodeToString(serializer, message))
    }

    private fun onMissingMessagesFetched(response: FetchMessagesResponse<M>) {
        if (response._isSuccessful == true) {
            response._data?.let { messages ->
                runLockingTask(mutex) {
                    if (messages.isNotEmpty()) {
                        ackMessages.addAll(messages)
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
            Napier.e("server error: ${response._error}")
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

    private fun isSenderPartOfThisChatAndIsntMe(userId: String?): Boolean {
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

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runInBackground {
                    acknowledgeMessages(ackMessages)
                    isSocketConnected = false
                    exposeSocketMessages = false
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.decodeFromString(serializer, text)
                if (isSenderPartOfThisChatAndIsntMe(message._sender)) {
                    ackMessages.add(message)
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

    class Builder<M: Message> {
        private var socketURL: String? = null

        private var chatServiceListener: ChatServiceListener<M>? = null

        private var me: String? = null
        private var receivers: List<String> = listOf()

        private var missingMessagesCaller: ChatEndpointCaller<M, FetchMessagesResponse<M>>? = null
        private var messageAckCaller: ChatEndpointCaller<List<M>, ChatResponse>? = null

        fun <R: ChatResponse> setMessageAckCaller(caller: ChatEndpointCaller<List<M>, R>): Builder<M> {
            messageAckCaller = cast(caller)
            return this
        }

        fun <R: FetchMessagesResponse<M>> setMissingMessagesCaller(caller: ChatEndpointCaller<M, R>): Builder<M> {
            missingMessagesCaller = cast(caller)
            return this
        }

        fun setUsername(userId: String): Builder<M> {
            me = userId
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

        fun build(serializer: KSerializer<M>): ChatServiceManager<M> {
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