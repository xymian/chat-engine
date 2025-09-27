package listeners

import ChatServiceErrorResponse
import models.ComparableMessage
import okhttp3.Response

interface ChatEngineEventListener<M: ComparableMessage> {
    fun onClose(code: Int, reason: String)
    fun onError(response: ChatServiceErrorResponse)
    fun onSent(message: M)
    fun onReceive(message: M)
    fun onDisconnect(t: Throwable, response: Response?)
    fun onConnect()
}
