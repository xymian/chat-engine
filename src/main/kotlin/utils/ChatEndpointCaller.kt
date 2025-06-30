package utils

import java.lang.Exception

interface ChatEndpointCaller<D, R> {
    suspend fun call(data: D?, handler: ResponseCallback<R>)

    interface ResponseCallback<T> {
        fun onResponse(response: T)
        fun onFailure(e: Exception?)
    }
}