import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import listeners.ChatServiceListener
import models.ChatResponse
import models.FetchMessagesResponse
import models.ComparableMessage
import okhttp3.*
import utils.*
import java.lang.Exception

class ChatServiceManager<M: ComparableMessage>
private constructor(private val serializer: KSerializer<M>) : IChatServiceManager<M> {

    private var receivedMessagesSet = mutableSetOf<M>()
    private var sendMessagesQueue = mutableSetOf<M>()
    private var ackMessages = mutableSetOf<M>()

    private var exportMessages = true
    private var preventMessageExportation: Boolean = false
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

    private var delay = 1000L
    private val maxDelay = 16000L

    private val coroutineScope = CoroutineScope(Dispatchers.Default) + SupervisorJob()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun connect() {
        coroutineScope.runInBackground {
            fetchMissingMessages {
                startSocket()
            }
        }
    }

    private fun startSocket() {
        socketURL?.let {
            if (!socketIsConnected) {
                socket = client.newWebSocket(Request.Builder().url(it).build(), webSocketListener())
            }
        }
    }

    override fun pause() {
        preventMessageExportation = true
        exportMessages = false
        coroutineScope.runInBackground {
            acknowledgeMessages(ackMessages.toList()) {

            }
        }
    }

    override fun resume() {
        preventMessageExportation = false
        coroutineScope.runInBackground {
            acknowledgeMessages(ackMessages.toList()) {}
            fetchMissingMessages {
                scheduleSocketReconnect()
            }
        }
    }

    override fun disconnect() {
        socket?.close(1000, "end session")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun fetchMissingMessages(completion: () -> Unit) {
        missingMessagesCaller?.call(
            handler = object: ResponseCallback<FetchMessagesResponse<M>> {
            override fun onResponse(response: FetchMessagesResponse<M>) {
                coroutineScope.runInBackground {
                    onMissingMessagesFetched(response, completion)
                }
            }

            override fun onFailure(e: Exception?) {
                Napier.e("error: ${e?.message}")
                coroutineScope.runOnMainThread {
                    chatServiceListener?.onError(
                        ChatServiceError.FETCH_MISSING_MESSAGES_FAILED, e?.message ?: ""
                    )
                }
            }
        })
    }

    private suspend fun acknowledgeMessages(messages: List<M>, completion: () -> Unit) {
        if (messages.isNotEmpty()) {
            messageAckCaller?.call(data = messages, handler = object : ResponseCallback<ChatResponse> {
                override fun onResponse(response: ChatResponse) {
                    if (response.isSuccessful == true) {
                        ackMessages = mutableSetOf()
                    }

                    coroutineScope.runOnMainThread {
                        completion()
                    }

                }

                override fun onFailure(e: Exception?) {
                    Napier.e("error: ${e?.message}")
                    coroutineScope.runOnMainThread {
                        chatServiceListener?.onError(
                            ChatServiceError.ACKNOWLEDGE_FAILED, e?.message ?: ""
                        )
                        completion()
                    }
                }
            })
        }
    }

    override suspend fun sendMessage(message: M) {
        if (message.sender == me) {
            coroutineScope.runInBackground {
                localStorageInstance?.store(message)
            }
        }
        if (socketState == SocketStates.NOT_CONNECTED || socketState == SocketStates.CLOSED) {
            sendMessagesQueue.add(message)
        } else {
            if (socketIsConnected) {
                if (sendMessagesQueue.isNotEmpty()) {
                    sendMessagesQueue.empty().let {
                        it.forEach { m ->
                            socket?.send(json.encodeToString(serializer, m))
                        }
                    }
                } else {
                    socket?.send(json.encodeToString(serializer, message))
                }
            }
        }
    }

    private suspend fun onMissingMessagesFetched(response: FetchMessagesResponse<M>, completion: () -> Unit) {
        if (response.isSuccessful == true) {
            response.data?.let { messages ->
                coroutineScope.runLockingTask(mutex) {
                    ackMessages.addAll(messages)
                }

                localStorageInstance?.store(messages)

                if (!socketIsConnected) {
                    coroutineScope.runLockingTask(mutex) {
                        handleMissingMessages(messages)
                        setExportedStatusIfNotPrevented(false)
                    }
                    completion()
                } else {
                    coroutineScope.runLockingTask(mutex) {
                        setExportedStatusIfNotPrevented(true)
                        handleMissingMessages(messages)
                    }
                }
            }
        } else {
            Napier.e("server error: ${response.message}")
        }
    }

    private fun handleMissingMessages(messages: List<M>) {
        if (exportMessages) {
            if (receivedMessagesSet.isNotEmpty()) {
                coroutineScope.runLockingTask(mutex) {
                    receivedMessagesSet.addAll(messages)
                    receivedMessagesSet.empty().let {
                        coroutineScope.runOnMainThread {
                            chatServiceListener?.onReceive(it)
                        }
                    }
                }
            } else {
                coroutineScope.runOnMainThread {
                    if (messages.isNotEmpty()) {
                        chatServiceListener?.onReceive(messages)
                    }
                }
            }
        } else {
            coroutineScope.runLockingTask(mutex) {
                receivedMessagesSet.addAll(messages)
            }
        }
    }

    private fun scheduleSocketReconnect() {
        coroutineScope.runInBackground {
            delay(delay)
            delay = (delay * 2).coerceAtMost(maxDelay)
            startSocket()
        }
    }

    private fun isSenderPartOfThisChatAndIsntMe(userId: String?): Boolean {
        return if (userId != me) {
            receivers.contains(userId)
        } else {
            false
        }
    }

    private fun setExportedStatusIfNotPrevented(value: Boolean) {
        if (!preventMessageExportation) {
            exportMessages = value
        }
    }

    private fun webSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                coroutineScope.runInBackground {
                    acknowledgeMessages(ackMessages.toList()) {
                        coroutineScope.runInBackground {
                            fetchMissingMessages {
                                scheduleSocketReconnect()
                            }
                        }
                    }
                }
                socketState = SocketStates.CLOSED
                setExportedStatusIfNotPrevented(false)
                coroutineScope.runOnMainThread {
                    chatServiceListener?.onClose(code, reason)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                coroutineScope.runInBackground {
                    acknowledgeMessages(ackMessages.toList()) {
                        coroutineScope.runInBackground {
                            fetchMissingMessages {
                                scheduleSocketReconnect()
                            }
                        }
                    }
                }
                socketState = SocketStates.FAILED
                setExportedStatusIfNotPrevented(false)
                coroutineScope.runOnMainThread {
                    chatServiceListener?.onDisconnect(t, response)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = json.decodeFromString(serializer, text)
                if (isSenderPartOfThisChatAndIsntMe(message.sender)) {
                    ackMessages.add(message)
                    coroutineScope.runInBackground {
                        localStorageInstance?.store(message)
                    }
                    handleMissingMessages(listOf(message))
                } else {
                    if (message.sender != me) {
                        coroutineScope.runOnMainThread {
                            chatServiceListener?.onError(
                                ChatServiceError.MESSAGE_LEAK_ERROR, "unknown message sender ${message.sender}"
                            )
                        }
                        disconnect()
                    } else {
                        coroutineScope.runInBackground {
                            localStorageInstance?.store(message)
                        }
                        coroutineScope.runOnMainThread {
                            chatServiceListener?.onSend(message)
                        }
                    }
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketState = SocketStates.CONNECTED
                coroutineScope.runOnMainThread {
                    chatServiceListener?.onConnect()
                }
                coroutineScope.runInBackground {
                    fetchMissingMessages {
                        scheduleSocketReconnect()
                    }
                }
            }
        }
    }

    class Builder<M: ComparableMessage> {
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
                this.me = this@Builder.me
                this.receivers = this@Builder.receivers
                this.missingMessagesCaller = this@Builder.missingMessagesCaller
                this.messageAckCaller = this@Builder.messageAckCaller
                this.localStorageInstance = this@Builder.localStorageInstance

                this.socketState = SocketStates.NOT_CONNECTED
            }
        }
    }
}