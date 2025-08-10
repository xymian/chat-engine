import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import listeners.ChatServiceListener
import models.ComparableMessage
import okhttp3.*
import utils.*
import java.lang.Exception

class ChatServiceManager<M: ComparableMessage>
private constructor(private val serializer: KSerializer<M>) : IChatServiceManager<M> {

    private var unexportedReceivedMessages = mutableSetOf<M>()
    private var unsentMessages = mutableSetOf<M>()

    private var shouldExportMessages = true
    val socketIsConnected: Boolean
        get() {
            return socketState == SocketState.CONNECTED
        }

    private var socketURL: String? = null
    private var socketState: SocketState? = null

    private var me: String? = null
    private var receivers: List<String> = listOf()
    private var timestampFormat: String? = null

    private var chatServiceListener: ChatServiceListener<M>? = null
    private var socketMessageReturner: SocketMessageReturner<M> = object: SocketMessageReturner<M> {

        override fun isReturnableSocketMessage(message: M) = false

        override fun returnMessage(message: M) = message
    }

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
                startSocket()
            }
        }
    }

    private fun scheduleServiceConnect() {
        if (!socketIsConnected) {
            coroutineScope.runInBackground {
                scheduleSocketReconnect()
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
        shouldExportMessages = false
    }

    override fun resume() {
        if (socketIsConnected) {
            coroutineScope.runLockingTask(mutex) {
                shouldExportMessages = true
                handleMissingMessages(listOf()) {}
            }
        } else {
            shouldExportMessages = false
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

    private fun handleMissingMessages(newMessages: List<M>, completion: () -> Unit) {
        if (shouldExportMessages) {
            if (unexportedReceivedMessages.isNotEmpty()) {
                unexportedReceivedMessages.addAll(newMessages)
                unexportedReceivedMessages.empty().let {
                    chatServiceListener?.onReceive(it)
                }
            } else {
                if (newMessages.isNotEmpty()) {
                    chatServiceListener?.onReceive(newMessages)
                }
            }
        } else {
            unexportedReceivedMessages.addAll(newMessages)
        }

        completion()
    }

    private fun scheduleSocketReconnect() {
        coroutineScope.runInBackground {
            if (delay < maxDelay) {
                delay(delay)
                delay = (delay * 2)
                startSocket()
            }
        }
    }

    private fun isSenderPartOfThisChatAndIsntMe(userId: String?): Boolean {
        return if (userId != me) {
            receivers.isEmpty() || receivers.contains(userId)
        } else {
            false
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
                onSocketMessageReceived(json.decodeFromString(serializer, text), socketMessageReturner)
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketState = SocketState.CONNECTED
                shouldExportMessages = true
                coroutineScope.runOnMainThread {
                    chatServiceListener?.onConnect()
                }
                sendPendingMessagesFirst(listOf())
            }
        }
    }

    private fun onSocketClosed(code: Int, reason: String) {
        socketState = SocketState.CLOSED
        coroutineScope.runOnMainThread {
            chatServiceListener?.onClose(code, reason)
        }
        scheduleServiceConnect()
    }

    private fun onSocketFailure(t: Throwable, response: Response?) {
        socketState = SocketState.FAILED
        coroutineScope.runOnMainThread {
            chatServiceListener?.onDisconnect(t, response)
        }
        scheduleServiceConnect()
    }

    private val returnedMessages = mutableListOf<M>()
    private fun onSocketMessageReceived(message: M, messageLabeler: SocketMessageReturner<M>) {
        if (isSenderPartOfThisChatAndIsntMe(message.sender)) {
            coroutineScope.runInBackground {
                localStorageInstance?.store(message)
            }
            val alreadyReturnedMessage = returnedMessages.find { it.timestamp == message.timestamp }
            if (alreadyReturnedMessage != null) {
                returnedMessages.remove(alreadyReturnedMessage)
                coroutineScope.runInBackground {
                    localStorageInstance?.store(message)
                }
                return
            }
            handleMissingMessages(listOf(message)) {
                if (messageLabeler.isReturnableSocketMessage(message)) {
                    val returnMessage = messageLabeler.returnMessage(message)
                    returnedMessages.add(returnMessage)
                    chatServiceListener?.returnMessage(returnMessage, shouldExportMessages)
                    socket?.send(json.encodeToString(serializer, returnMessage))
                }
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
        private var socketMessageReturner: SocketMessageReturner<M>? = null

        private var me: String? = null
        private var receivers: List<String> = listOf()
        private var timestampFormat: String? = null

        private var localStorageInstance: ILocalStorage<M>? = null

        fun setMessageLabeler(labeler: SocketMessageReturner<M>): Builder<M> {
            socketMessageReturner = labeler
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
                this.localStorageInstance = this@Builder.localStorageInstance
                this.timestampFormat = this@Builder.timestampFormat
                this@Builder.socketMessageReturner?.let {
                    this.socketMessageReturner = it
                }
                this.socketState = SocketState.NOT_CONNECTED
            }
        }
    }
}