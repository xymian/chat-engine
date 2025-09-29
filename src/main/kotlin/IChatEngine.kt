import listeners.ChatEngineEventListener
import models.ComparableMessage

interface IChatEngine<M: ComparableMessage> {
    fun setListener(listener: ChatEngineEventListener<M>)
    fun connect()
    fun disconnect()
    fun sendMessage(message: M)
    fun returnMessage(message: M)
}