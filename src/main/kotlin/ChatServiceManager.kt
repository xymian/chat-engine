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

    private var exportMessages = true
    private var preventMessageExportation: Boolean = false
    val socketIsConnected: Boolean
        get() {
            return socketState == SocketStates.CONNECTED
        }

    private var missingMessagesCaller: ChatEndpointCaller<MessagesResponse<M>>? = null

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

    private fun scheduleServiceConnect() {
        if (!socketIsConnected) {
            coroutineScope.runInBackground {
                fetchMissingMessages {
                    scheduleSocketReconnect()
                }
            }
        }
    }

    private fun startSocket() {
        if (!socketIsConnected) {
            socketURL?.let {
                if (!socketIsConnected) {
                    socket = client.newWebSocket(Request.Builder().url(it).build(), webSocketListener())
                }
            }
        }
    }

    override fun pause() {
        preventMessageExportation = true
        exportMessages = false
    }

    override fun resume() {
        preventMessageExportation = false
        if (socketIsConnected) {
            coroutineScope.runLockingTask(mutex) {
                setExportedStatusIfNotPrevented(true)
                handleMissingMessages(listOf())
            }
        } else {
            connect()
            coroutineScope.runInBackground {
                sendPendingMessagesFirst(listOf())
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

    override fun sendMessage(message: M) {
        if (message.sender == me) {
            coroutineScope.runInBackground {
                localStorageInstance?.store(message)
            }
        } else {
            throw Exception("sender has changed: not allowed")
        }
        coroutineScope.runInBackground {
            sendPendingMessagesFirst(listOf(message))
        }
    }

    private fun tryToSendMessage(message: M) {
        if (!socketIsConnected) {
            unsentMessages.add(message)
        } else {
            socket?.send(json.encodeToString(serializer, message))
        }
    }

    private fun sendPendingMessagesFirst(newMessages: List<M>) {
        unsentMessages.addAll(newMessages)
        unsentMessages.empty().let {
            it.sortedBy { m -> m.timestamp }.forEach { m ->
                tryToSendMessage(m)
            }
        }
    }

    private fun onMissingMessagesFetched(response: MessagesResponse<M>, completion: () -> Unit) {
        if (response.isSuccessful == true) {
            response.data?.let { messages ->
                coroutineScope.runLockingTask(mutex) {
                    coroutineScope.runInBackground {
                        localStorageInstance?.store(messages)
                    }
                }

                if (!socketIsConnected) {
                    chatServiceListener?.let {
                        unsentMessages.addAll(it.onReturnMissingMessages(messages))
                    }
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
                        override fun isReturnableSocketMessage(message: M) = false

                        override fun getReturnMessageFromCurrent(message: M, reason: ReturnMessageReason?) = message

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
                sendPendingMessagesFirst(listOf())
            }
        }
    }

    private fun onSocketClosed(code: Int, reason: String) {
        socketState = SocketStates.CLOSED
        setExportedStatusIfNotPrevented(false)
        coroutineScope.runOnMainThread {
            chatServiceListener?.onClose(code, reason)
        }
        scheduleServiceConnect()
    }

    private fun onSocketFailure(t: Throwable, response: Response?) {
        socketState = SocketStates.FAILED
        setExportedStatusIfNotPrevented(false)
        coroutineScope.runOnMainThread {
            chatServiceListener?.onDisconnect(t, response)
        }
        scheduleServiceConnect()
    }

    private val returnedMessages = mutableListOf<M>()
    private fun onSocketMessageReceived(message: M, messageLabeler: SocketMessageLabeler<M>) {
        if (isSenderPartOfThisChatAndIsntMe(message.sender)) {
            coroutineScope.runInBackground {
                localStorageInstance?.store(message)
            }
            val alreadyProcessedMessage = returnedMessages.find { it.timestamp == message.timestamp }
            if (alreadyProcessedMessage != null) {
                returnedMessages.remove(alreadyProcessedMessage)
                coroutineScope.runInBackground {
                    localStorageInstance?.store(message)
                }
                return
            }
            handleMissingMessages(listOf(message))
            if (messageLabeler.isReturnableSocketMessage(message)) {
                val returnMessage = messageLabeler.getReturnMessageFromCurrent(message, messageLabeler.returnReason(message))
                returnedMessages.add(returnMessage)
                chatServiceListener?.returnMessage(returnMessage, exportMessages)
                socket?.send(json.encodeToString(serializer, returnMessage))
            }
        } else {
            when (message.sender) {
                me -> {
                    coroutineScope.runInBackground {
                        localStorageInstance?.store(message)
                    }
                    coroutineScope.runOnMainThread {
                        chatServiceListener?.onSent(message)
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
    }

    class Builder<M: ComparableMessage> {
        private var socketURL: String? = null

        private var chatServiceListener: ChatServiceListener<M>? = null
        private var socketMessageLabeler: SocketMessageLabeler<M>? = null

        private var me: String? = null
        private var receivers: List<String> = listOf()
        private var timestampFormat: String? = null

        private var missingMessagesCaller: ChatEndpointCaller<MessagesResponse<M>>? = null

        private var localStorageInstance: ILocalStorage<M>? = null

        fun setMessageLabeler(labeler: SocketMessageLabeler<M>): Builder<M> {
            socketMessageLabeler = labeler
            return this
        }

        fun setTimestampFormat(pattern: String): Builder<M> {
            timestampFormat = pattern
            return this
        }

        fun <N: ComparableMessage> setStorageInterface(storage: ILocalStorage<N>): Builder<M> {
            localStorageInstance = cast(storage)
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
                this.localStorageInstance = this@Builder.localStorageInstance
                this.timestampFormat = this@Builder.timestampFormat
                this.socketMessageLabeler = this@Builder.socketMessageLabeler
                this.socketState = SocketStates.NOT_CONNECTED
            }
        }
    }
}