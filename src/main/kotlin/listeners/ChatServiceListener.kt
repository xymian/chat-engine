package listeners

import ChatServiceError
import ChatServiceErrorResponse
import models.ComparableMessage
import okhttp3.Response
import java.lang.Exception

interface ChatServiceListener<M: ComparableMessage> {
    fun onClose(code: Int, reason: String)
    fun onError(response: ChatServiceErrorResponse)
    fun onSend(message: M)
    fun onReceive(message: M)
    fun onReceive(messages: List<M>)
    fun onDisconnect(t: Throwable, response: Response?)
    fun onConnect()
}
