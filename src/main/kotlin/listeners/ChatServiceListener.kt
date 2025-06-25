package listeners

import models.Message

interface ChatServiceListener {
    fun onSend(message: Message)
    fun onReceiveMessage(message: Message)
    fun onDisconnect()
    fun onConnect()
}
