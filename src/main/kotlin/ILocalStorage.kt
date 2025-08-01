import models.ComparableMessage

interface ILocalStorage<M: ComparableMessage> {
    suspend fun store(message: M)
    suspend fun store(messages: List<M>)
}