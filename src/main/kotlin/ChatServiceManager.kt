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

    private var unexportedReceivedMessages = mutableSetOf<M>()
    private var unsentMessages = mutableSetOf<M>()
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
    private var timestampFormat: String? = null

    private var chatServiceListener: ChatServiceListener<M>? = null

    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var localStorageInstance: ILocalStorage<M>? = null

    private val mutex = Mutex()

    private var delay = 1000L
    private val maxDelay = 16000L

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun connectAndSend(messages: List<M>) {
        unsentMessages.addAll(messages)
        connect()
    }

    override fun connect() {
        if (!socketIsConnected) {
            coroutineScope.runInBackground {
                fetchMissingMessages {
                    startSocket()
                }
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
                startSocket()
            }
            sendPendingMessages()
        }
    }

    override fun disconnect() {
        socket?.close(1000, "end session")
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun fetchMissingMessages(completion: () -> Unit) {
        missingMessagesCaller?.call(
            handler = object: ResponseCallback<FetchMessagesResponse<M>> {
            override fun onResponse(response: FetchMessagesResponse<M>) {
                onMissingMessagesFetched(response, completion)
            }

                override fun onFailure(response: ChatServiceErrorResponse) {
                    Napier.e("error: ${response.exception?.message}")
                    coroutineScope.runOnMainThread {
                        chatServiceListener?.onError(response)
                    }
                }
            })
    }

    private suspend fun acknowledgeMessages(messages: List<M>, completion: () -> Unit) {
        if (messages.isNotEmpty()) {
            messageAckCaller?.call(data = messages, handler = object : ResponseCallback<ChatResponse> {
                override fun onResponse(response: ChatResponse) {
                    if (response.isSuccessful == true) {
                        chatServiceListener?.onRecipientMessagesAcknowledged(messages)
                        ackMessages = mutableSetOf()
                    }

                    coroutineScope.runOnMainThread {
                        completion()
                    }

                }

                override fun onFailure(response: ChatServiceErrorResponse) {
                    Napier.e("error: ${response.exception?.message}")
                    coroutineScope.runOnMainThread {
                        chatServiceListener?.onError(response)
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
        } else {
            throw Exception("sender has changed: not allowed")
        }
        sendPendingMessages()
        tryToSendMessage(message)
    }

    private fun tryToSendMessage(message: M) {
        if (!socketIsConnected) {
            unsentMessages.add(message)
        } else {
            socket?.send(json.encodeToString(serializer, message))
        }
    }

    private fun sendPendingMessages() {
        unsentMessages.empty().let {
            it.forEach { m ->
                tryToSendMessage(m)
            }
        }
    }

    private fun onMissingMessagesFetched(response: FetchMessagesResponse<M>, completion: () -> Unit) {
        if (response.isSuccessful == true) {
            response.data?.let { messages ->
                coroutineScope.runLockingTask(mutex) {
                    ackMessages.addAll(messages)
                }
                coroutineScope.runInBackground {
                    localStorageInstance?.store(messages)
                }

                if (!socketIsConnected) {
                    coroutineScope.runLockingTask(mutex) {
                        setExportedStatusIfNotPrevented(true)
                        handleMissingMessages(messages)
                        setExportedStatusIfNotPrevented(false)
                    }
                    completion()
                    coroutineScope.runInBackground {
                        fetchMissingMessages {
                            startSocket()
                        }
                    }
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
            if (unexportedReceivedMessages.isNotEmpty()) {
                coroutineScope.runLockingTask(mutex) {
                    unexportedReceivedMessages.addAll(messages)
                    unexportedReceivedMessages.empty().let {
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
                unexportedReceivedMessages.addAll(messages)
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
                                startSocket()
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
                                startSocket()
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
                                ChatServiceErrorResponse(
                                    statusCode = -1, null, ChatServiceError.MESSAGE_LEAK_ERROR.name,
                                    "unknown message sender ${message.sender}"
                                )
                            )
                        }
                        disconnect()
                    } else {
                        coroutineScope.runInBackground {
                            localStorageInstance?.store(message)
                        }
                        coroutineScope.runOnMainThread {
                            chatServiceListener?.onSent(listOf(message))
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
                    sendPendingMessages()
                }
            }
        }
    }

    class Builder<M: ComparableMessage> {
        private var socketURL: String? = null

        private var chatServiceListener: ChatServiceListener<M>? = null

        private var me: String? = null
        private var receivers: List<String> = listOf()
        private var timestampFormat: String? = null

        private var missingMessagesCaller: ChatEndpointCaller<FetchMessagesResponse<M>>? = null
        private var messageAckCaller: ChatEndpointCallerWithData<List<M>, ChatResponse>? = null

        private var localStorageInstance: ILocalStorage<M>? = null

        fun setTimestampFormat(pattern: String): Builder<M> {
            timestampFormat = pattern
            return this
        }

        fun <N: ComparableMessage> setStorageInterface(storage: ILocalStorage<N>): Builder<M> {
            localStorageInstance = cast(storage)
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
                this.timestampFormat = this@Builder.timestampFormat

                this.socketState = SocketStates.NOT_CONNECTED
            }
        }
    }
}