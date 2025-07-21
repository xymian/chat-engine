package models

import kotlinx.serialization.Serializable

@Serializable
abstract class MessagesResponse<M: ComparableMessage> {
    abstract val data: List<M>?
    abstract val isSuccessful: Boolean?
    abstract val message: String?
}