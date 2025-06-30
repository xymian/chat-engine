package models

import kotlinx.serialization.Serializable

@Serializable
open class ChatResponse(
    open val _data: String?,
    open val _isSuccessful: Boolean?,
    open val _error: String?
)

@Serializable
open class FetchMessagesResponse<M: Message>(
    val _data: List<M>?,
    val _isSuccessful: Boolean?,
    val _error: String?
)