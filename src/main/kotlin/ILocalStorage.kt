import models.Message

interface ILocalStorage<M: Message> {
    fun deleteMessage(messageReference: String) {}
    fun retrievePaginatedMessages(page: Int, pageSize: Int)
    fun store(message: M)
    fun store(messages: List<M>)
}