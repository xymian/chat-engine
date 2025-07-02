import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
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

    private var receivedMessagesQueue = PriorityQueue<M>()
    private var sendMessagesQueue = PriorityQueue<M>()
    private var ackMessages = mutableListOf<M>()

    private var exposeSocketMessages = true
    private val socketIsConnected: Boolean
        get() {
            return socketState == SocketStates.CONNECTED
        }

    private var missingMessagesCaller: ChatEndpointCaller<FetchMessagesResponse<M>>? = null
    private var messageAckCaller: ChatEndpointCallerWithData<List<M>, ChatResponse>? = null

    private var socketURL: String? = null
    private var socketState: SocketStates? = null

    private var me: String? = null
    private var receivers: List<String> = listOf()

    private var chatServiceListener: ChatServiceListener<M>? = null

    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var localStorageInstance: ILocalStorage<M>? = null

    private val mutex = Mutex()

    private var delay = 1_000L
    private val maxDelay = 16_000L

    private val coroutineScope = CoroutineScope(Dispatchers.Main) + SupervisorJob()

    override fun connect() {
        CoroutineScope(Dispatchers.IO).launch {
            fetchMissingMessages()
        }
    }

    private fun startSocket() {
        socketURL?.let {
            if (!socketIsConnected) {
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
            handler = object: ResponseCallback<FetchMessagesResponse<M>> {
            override fun onResponse(response: FetchMessagesResponse<M>) {
                coroutineScope.runOnMainThread {
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
        messageAckCaller?.call(data = messages, handler = object: ResponseCallback<ChatResponse> {
            override fun onResponse(response: ChatResponse) {
                if (response._isSuccessful == true) {
                    ackMessages = mutableListOf()
                }
                coroutineScope.runInBackground {
                    fetchMissingMessages()
                }
            }

            override fun onFailure(e: Exception?) {
                Napier.e("error: ${e?.message}")
                chatServiceListener?.onError(
                    ChatServiceError.ACKNOWLEDGE_FAILED, e?.message ?: ""
                )
                coroutineScope.runInBackground {
                    fetchMissingMessages()
                }
            }
        })
    }

    override fun sendMessage(message: M) {
        if (message._sender == me) {
            coroutineScope.runInBackground {
                localStorageInstance?.store(message)
            }
        }
        if (socketState == SocketStates.NOT_CONNECTED || socketState == SocketStates.CLOSED) {
            sendMessagesQueue.add(message)
        } else {
            if (socketIsConnected) {
                if (sendMessagesQueue.isNotEmpty()) {
                    sendMessagesQueue.emptyQueue().let {
                        it.forEach { m ->
                            socket?.send(Json.encodeToString(serializer, m))
                        }
                    }
                } else {
                    socket?.send(Json.encodeToString(serializer, message))
                }
            }
        }
    }

    private fun onMissingMessagesFetched(response: FetchMessagesResponse<M>) {
        if (response._isSuccessful == true) {
            response._data?.let { messages ->
                coroutineScope.runLockingTask(mutex) {
                    if (messages.isNotEmpty()) {
                        ackMessages.addAll(messages)
                    }
                }
                coroutineScope.runInBackground {
                    localStorageInstance?.store(messages)
                }
                if (exposeSocketMessages) {
                    chatServiceListener?.onReceive(messages)
                } else {
                    coroutineScope.runLockingTask(mutex) {
                        receivedMessagesQueue.addAll(messages)
                    }
                }
                if (!socketIsConnected) {
                    exposeSocketMessages = false
                    scheduleSocketReconnect()
                } else {
                    coroutineScope.runLockingTask(mutex) {
                        if (receivedMessagesQueue.isNotEmpty()) {
                            receivedMessagesQueue.emptyQueue().let {
                                coroutineScope.runOnMainThread {
                                    chatServiceListener?.onReceive(it)
                                }
                            }
                        } else {
                            coroutineScope.runOnMainThread {
                                chatServiceListener?.onReceive(messages)
                            }
                        }
                        exposeSocketMessages = true
                    }
                }
            }
        } else {
            Napier.e("server error: ${response._message}")
        }
    }

    private fun scheduleSocketReconnect() {
        coroutineScope.runInBackground {
            delay(delay)
            delay = (delay * 2).coerceAtMost(maxDelay)
            startSocket()
        }
    }

    private fun scheduleMissingMessageRetry() {
        coroutineScope.runInBackground {
            delay = (delay * 2).coerceAtMost(maxDelay)
            fetchMissingMessages()
        }
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
                socketState = SocketStates.CLOSED
                exposeSocketMessages = false
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                coroutineScope.runInBackground {
                    acknowledgeMessages(ackMessages)
                    socketState = SocketStates.FAILED
                    exposeSocketMessages = false
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.decodeFromString(serializer, text)
                if (isSenderPartOfThisChatAndIsntMe(message._sender)) {
                    ackMessages.add(message)
                    coroutineScope.runInBackground {
                        localStorageInstance?.store(message)
                    }

                    if (exposeSocketMessages) {
                        coroutineScope.runLockingTask(mutex) {
                            receivedMessagesQueue.add(message)
                            if (receivedMessagesQueue.isNotEmpty()) {
                                receivedMessagesQueue.emptyQueue().let {
                                    coroutineScope.runOnMainThread {
                                        chatServiceListener?.onReceive(it)
                                    }
                                }
                            } else {
                                coroutineScope.runOnMainThread {
                                    chatServiceListener?.onReceive(message)
                                }
                            }
                        }
                    } else {
                        coroutineScope.runLockingTask(mutex) {
                            receivedMessagesQueue.add(message)
                        }
                    }
                } else {
                    if (message._sender != me) {
                        chatServiceListener?.onError(
                            ChatServiceError.MESSAGE_LEAK_ERROR, "unknown message sender ${message._sender}"
                        )
                        disconnect()
                    } else {
                        chatServiceListener?.onSend(message)
                    }
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketState = SocketStates.CONNECTED
                chatServiceListener?.onConnect()
                coroutineScope.runInBackground {
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

        private var missingMessagesCaller: ChatEndpointCaller<FetchMessagesResponse<M>>? = null
        private var messageAckCaller: ChatEndpointCallerWithData<List<M>, ChatResponse>? = null

        private var localStorageInstance: ILocalStorage<M>? = null

        fun setStorageInterface(storage: ILocalStorage<M>): Builder<M> {
            localStorageInstance = storage
            return this
        }

        fun <R: ChatResponse> setMessageAckCaller(caller: ChatEndpointCallerWithData<List<M>, R>): Builder<M> {
            messageAckCaller = cast(caller)
            return this
        }

        fun <R: FetchMessagesResponse<M>> setMissingMessagesCaller(caller: ChatEndpointCaller<R>): Builder<M> {
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
                    this.socketState = SocketStates.NOT_CONNECTED
                }
                this.me = this@Builder.me
                this.receivers = this@Builder.receivers
                this.missingMessagesCaller = this@Builder.missingMessagesCaller
                this.messageAckCaller = this@Builder.messageAckCaller
                this.localStorageInstance = this@Builder.localStorageInstance
            }
        }
    }
}