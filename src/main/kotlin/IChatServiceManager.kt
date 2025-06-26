import models.Message

interface IChatServiceManager<M: Message> {
    fun connect()
    fun disconnect()
    fun sendMessage(message: M)
}