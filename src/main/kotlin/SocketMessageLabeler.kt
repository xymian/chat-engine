import models.ComparableMessage

interface SocketMessageLabeler<M: ComparableMessage> {
    fun isReturnableSocketMessage(message: M): Boolean
    fun getReturnMessageFromCurrent(message: M, reason: ReturnMessageReason?): M
    fun returnReason(message: M): ReturnMessageReason?
}