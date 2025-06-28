import models.Message

interface ILocalStorage<M: Message> {
    suspend fun deleteMessage(messageReference: String) {}
    suspend fun retrievePaginatedMessages(page: Int, pageSize: Int)
    suspend fun store(message: M)
    suspend fun store(messages: List<M>)
}