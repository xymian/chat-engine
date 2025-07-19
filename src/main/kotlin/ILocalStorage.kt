import models.ComparableMessage

interface ILocalStorage<M: ComparableMessage> {
    suspend fun setAsSeen(vararg messageRefToChatRef: Pair<String, String>)
    suspend fun setAsSent(vararg messageRefToChatRef: Pair<String, String>)
    suspend fun store(message: M)
    suspend fun store(messages: List<M>)
}