package listeners

import models.Message

interface ChatServiceListener<M: Message> {
    fun onSend(message: M)
    fun onReceive(message: M)
    fun onDisconnect()
    fun onConnect()
}
