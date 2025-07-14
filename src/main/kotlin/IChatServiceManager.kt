import models.ComparableMessage

interface IChatServiceManager<M: ComparableMessage> {
    fun connect()
    fun disconnect()
    suspend fun sendMessage(message: M)
}