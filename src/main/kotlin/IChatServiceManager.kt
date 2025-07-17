import models.ComparableMessage

interface IChatServiceManager<M: ComparableMessage> {
    fun resume()
    fun pause()
    fun connect()
    fun disconnect()
    suspend fun sendMessage(message: M)
}