import models.ComparableMessage

interface IChatEngine<M: ComparableMessage> {
    fun connect()
    fun disconnect()
    fun sendMessage(message: M)
    fun returnMessage(message: M)
}