import models.ComparableMessage

interface IChatServiceManager<M: ComparableMessage> {
    fun resume()
    fun pause()
    fun connect()
    fun disconnect()
    fun sendMessage(message: M)
}