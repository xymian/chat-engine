package listeners

import ChatServiceErrorResponse
import models.ComparableMessage
import okhttp3.Response

interface ChatServiceListener<M: ComparableMessage> {
    fun onRead(messages:List<M>)
    fun onDelivered(messages: List<M>)
    fun onRecipientMessagesAcknowledged(messages: List<M>)
    fun onClose(code: Int, reason: String)
    fun onError(response: ChatServiceErrorResponse)
    fun onSent(messages: List<M>)
    fun onReceive(message: M)
    fun onReceive(messages: List<M>)
    fun onDisconnect(t: Throwable, response: Response?)
    fun onConnect()
}
