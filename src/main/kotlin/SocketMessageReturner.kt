import models.ComparableMessage

interface SocketMessageReturner<M: ComparableMessage> {
    fun isReturnableSocketMessage(message: M): Boolean
    fun returnMessage(message: M): M
}