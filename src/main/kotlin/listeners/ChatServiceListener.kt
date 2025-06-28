package listeners

import ChatServiceError
import models.Message

interface ChatServiceListener<M: Message> {
    fun onError(error: ChatServiceError, message: String)
    fun onSend(message: M)
    fun onReceive(message: M)
    fun onReceive(messages: List<M>)
    fun onDisconnect()
    fun onConnect()
}
