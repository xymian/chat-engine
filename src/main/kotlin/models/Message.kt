package models

import kotlinx.serialization.Serializable

@Serializable
abstract class Message(
    val messageTimestamp: String,
    val messageId: String,
)
