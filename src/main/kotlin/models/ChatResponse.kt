package models

import kotlinx.serialization.Serializable


@Serializable
abstract class ChatResponse {
    abstract val data: String?
    abstract val isSuccessful: Boolean?
    abstract val message: String?
}

@Serializable
abstract class FetchMessagesResponse<M: ComparableMessage> {
    abstract val data: List<M>?
    abstract val isSuccessful: Boolean?
    abstract val message: String?
}