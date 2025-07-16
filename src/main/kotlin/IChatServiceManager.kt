import models.ComparableMessage

interface IChatServiceManager<M: ComparableMessage> {
    fun resume()
    fun pause()
    fun connect()
    suspend fun sendMessage(message: M)
}