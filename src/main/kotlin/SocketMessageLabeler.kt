import models.ComparableMessage

interface SocketMessageLabeler<M: ComparableMessage> {
    fun isSocketReturnableMessage(message: M): Boolean
    fun getReturnMessageFromCurrent(message: M): M
    fun returnReason(message: M): ReturnMessageReason?
}