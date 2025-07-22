import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import listeners.ChatServiceListener
import models.MessagesResponse
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
    val socketIsConnected: Boolean
        get() {
            return socketState == SocketStates.CONNECTED
        }

    private var missingMessagesCaller: ChatEndpointCaller<MessagesResponse<M>>? = null
    private var messageAckCaller: ChatEndpointCallerWithData<List<M>, MessagesResponse<M>>? = null
    private var markAsDeliveredCaller: ChatEndpointCallerWithData<List<M>, MessagesResponse<M>>? = null

    private var socketURL: String? = null
    private var socketState: SocketStates? = null

    private var me: String? = null
    private var receivers: List<String> = listOf()
    private var timestampFormat: String? = null

    private var chatServiceListener: ChatServiceListener<M>? = null
    private var socketMessageLabeler: SocketMessageLabeler<M>? = null

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
            acknowledgeMessages {}
        }
    }

    override fun resume() {
        preventMessageExportation = false
        coroutineScope.runInBackground {
            acknowledgeMessages {}
        }
        if (socketIsConnected) {
            coroutineScope.runLockingTask(mutex) {
                setExportedStatusIfNotPrevented(true)
                handleMissingMessages(listOf())
            }
        } else {
            connect()
            coroutineScope.runInBackground {
                sendPendingMessages()
            }
        }
    }

    override fun disconnect() {
        socket?.close(1000, "end session")
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun fetchMissingMessages(completion: () -> Unit) {
        missingMessagesCaller?.call(
            handler = object: ResponseCallback<MessagesResponse<M>> {
            override fun onResponse(response: MessagesResponse<M>) {
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

    private suspend fun acknowledgeMessages(completion: () -> Unit) {
        val messages = ackMessages.toList()
        if (messages.isNotEmpty()) {
            messageAckCaller?.call(data = messages, handler = object : ResponseCallback<MessagesResponse<M>> {

                override fun onResponse(response: MessagesResponse<M>) {
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

    override fun sendMessage(message: M) {
        if (message.sender == me) {
            coroutineScope.runInBackground {
                localStorageInstance?.store(message)
            }
        } else {
            throw Exception("sender has changed: not allowed")
        }
        coroutineScope.runInBackground {
            sendPendingMessages()
            tryToSendMessage(message)
        }
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

    private fun onMissingMessagesFetched(response: MessagesResponse<M>, completion: () -> Unit) {
        if (response.isSuccessful == true) {
            response.data?.let { messages ->
                coroutineScope.runLockingTask(mutex) {
                    ackMessages.addAll(messages)
                    coroutineScope.runInBackground {
                        localStorageInstance?.store(messages)
                        acknowledgeMessages {}
                    }
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

    private fun handleMissingMessages(newMessages: List<M>) {
        if (exportMessages) {
            if (unexportedReceivedMessages.isNotEmpty()) {
                coroutineScope.runLockingTask(mutex) {
                    unexportedReceivedMessages.addAll(newMessages)
                    unexportedReceivedMessages.empty().let {
                        coroutineScope.runOnMainThread {
                            chatServiceListener?.onReceive(it)
                        }
                    }
                }
            } else {
                coroutineScope.runOnMainThread {
                    if (newMessages.isNotEmpty()) {
                        chatServiceListener?.onReceive(newMessages)
                    }
                }
            }
        } else {
            coroutineScope.runLockingTask(mutex) {
                unexportedReceivedMessages.addAll(newMessages)
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
                onSocketClosed(code, reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onSocketFailure(t, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = json.decodeFromString(serializer, text)
                val messageLabeler = if (socketMessageLabeler == null) {
                    val labeler = object: SocketMessageLabeler<M> {
                        override fun isSocketReturnableMessage(message: M) = false

                        override fun getReturnMessageFromCurrent(message: M) = message

                        override fun returnReason(message: M) = null
                    }
                    labeler
                } else socketMessageLabeler!!

                onSocketMessageReceived(message, messageLabeler)
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

    private fun onSocketClosed(code: Int, reason: String) {
        coroutineScope.runInBackground {
            acknowledgeMessages {
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

    private fun onSocketFailure(t: Throwable, response: Response?) {
        coroutineScope.runInBackground {
            acknowledgeMessages {
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

    private fun onSocketMessageReceived(message: M, messageLabeler: SocketMessageLabeler<M>) {
        if (messageLabeler.isSocketReturnableMessage(message)) {
            if (isSenderPartOfThisChatAndIsntMe(message.sender)) {
                socket?.send(json.encodeToString(serializer, messageLabeler.getReturnMessageFromCurrent(message)))
                ackMessages.add(message)
                coroutineScope.runInBackground {
                    localStorageInstance?.store(message)
                }
                handleMissingMessages(listOf(message))
            } else {
                when (message.sender) {
                    me -> {
                        coroutineScope.runInBackground {
                            localStorageInstance?.store(message)
                        }
                        coroutineScope.runOnMainThread {
                            chatServiceListener?.onSent(listOf(message))
                        }
                    }
                    else -> {
                        coroutineScope.runOnMainThread {
                            chatServiceListener?.onError(
                                ChatServiceErrorResponse(
                                    statusCode = -1, null, ChatServiceError.MESSAGE_LEAK_ERROR.name,
                                    "unknown message sender ${message.sender}"
                                )
                            )
                        }
                        disconnect()
                    }
                }
            }
        } else {
            coroutineScope.runInBackground {
                chatServiceListener?.onMessageReturned(message, messageLabeler.returnReason(message))
            }
        }
    }

    class Builder<M: ComparableMessage> {
        private var socketURL: String? = null

        private var chatServiceListener: ChatServiceListener<M>? = null

        private var me: String? = null
        private var receivers: List<String> = listOf()
        private var timestampFormat: String? = null

        private var missingMessagesCaller: ChatEndpointCaller<MessagesResponse<M>>? = null
        private var messageAckCaller: ChatEndpointCallerWithData<List<M>, MessagesResponse<M>>? = null
        private var markAsDeliveredCaller: ChatEndpointCallerWithData<List<M>, MessagesResponse<M>>? = null

        private var localStorageInstance: ILocalStorage<M>? = null

        fun setTimestampFormat(pattern: String): Builder<M> {
            timestampFormat = pattern
            return this
        }

        fun <N: ComparableMessage> setStorageInterface(storage: ILocalStorage<N>): Builder<M> {
            localStorageInstance = cast(storage)
            return this
        }

        fun <R: MessagesResponse<M>> setMessageAckCaller(caller: ChatEndpointCallerWithData<List<M>, R>): Builder<M> {
            messageAckCaller = cast(caller)
            return this
        }

        fun <R: MessagesResponse<M>> setMissingMessagesCaller(caller: ChatEndpointCaller<R>): Builder<M> {
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
                this.markAsDeliveredCaller = this@Builder.markAsDeliveredCaller
                this.localStorageInstance = this@Builder.localStorageInstance
                this.timestampFormat = this@Builder.timestampFormat

                this.socketState = SocketStates.NOT_CONNECTED
            }
        }
    }
}