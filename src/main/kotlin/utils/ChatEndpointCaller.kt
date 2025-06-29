package utils

import java.lang.Exception

interface ChatEndpointCaller {
    suspend fun <T, D> call(data: D?, handler: ResponseCallback<T>)

    interface ResponseCallback<T> {
        fun onResponse(response: T)
        fun onFailure(e: Exception?)
    }
}