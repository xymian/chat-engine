import models.Message

interface ILocalStorage<M: Message> {
    suspend fun store(message: M)
    suspend fun store(messages: List<M>)
}