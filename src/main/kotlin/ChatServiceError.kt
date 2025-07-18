import java.lang.Exception

enum class ChatServiceError {
    FETCH_MISSING_MESSAGES_FAILED, ACKNOWLEDGE_FAILED, MESSAGE_LEAK_ERROR, LOCAL_SAVE_FAILED
}

data class ChatServiceErrorResponse(
    val statusCode: Int,
    val exception: Exception?,
    val reason: String,
    val message: String
)