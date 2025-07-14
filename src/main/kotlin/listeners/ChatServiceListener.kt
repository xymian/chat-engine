package listeners

import ChatServiceError
import models.ComparableMessage
import okhttp3.Response

interface ChatServiceListener<M: ComparableMessage> {
    fun onClose(code: Int, reason: String)
    fun onError(error: ChatServiceError, message: String)
    fun onSend(message: M)
    fun onReceive(message: M)
    fun onReceive(messages: List<M>)
    fun onDisconnect(t: Throwable, response: Response?)
    fun onConnect()
}
