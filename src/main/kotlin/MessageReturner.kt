import models.ComparableMessage

interface MessageReturner<M: ComparableMessage> {
    fun isMessageReturnable(message: M): Boolean
    fun returnMessage(message: M): M
}
