package models

import kotlinx.serialization.Serializable


@Serializable
open class ChatResponse(
    val _data: String?,
    val _isSuccessful: Boolean?,
    val _message: String?
)

@Serializable
open class FetchMessagesResponse<M: Message>(
    val _data: List<M>?,
    val _isSuccessful: Boolean?,
    val _message: String?
)