package models

open class ChatResponse(
    open val _data: Any?,
    open val _isSuccessful: Boolean,
    open val _error: Exception? = null
)

open class FetchMessagesResponse<M: Message>(
    override val _data: List<M>?,
    override val _isSuccessful: Boolean,
    override val _error: Exception? = null
): ChatResponse(_data, _isSuccessful, _error)