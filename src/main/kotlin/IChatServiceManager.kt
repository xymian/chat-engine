import models.ComparableMessage

interface IChatServiceManager<M: ComparableMessage> {
    fun connect()
    fun disconnect()
    fun sendMessage(message: M)
    fun returnMessage(message: M)
}