package models

import kotlinx.serialization.Serializable

@Serializable
abstract class Message(
    val _timestamp: String,
    val _messageId: String,
    val _sender: String,
    val _receiver: String,
    val _message: String
)
