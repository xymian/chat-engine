package listeners

import ChatServiceError
import models.Message

interface ChatServiceListener<M: Message> {
    fun onMissingMessagesFetched(messages: List<M>)
    fun onError(error: ChatServiceError, message: String)
    fun onSend(message: M)
    fun onReceive(message: M)
    fun onDisconnect()
    fun onConnect()
}
