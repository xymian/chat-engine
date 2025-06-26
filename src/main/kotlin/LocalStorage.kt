import models.Message

interface LocalStorage<M: Message> {
    fun deleteMessage(messageReference: String) {}
    fun retrievePaginatedMessages(page: Int, pageSize: Int)
    fun store(messages: List<M>)
}