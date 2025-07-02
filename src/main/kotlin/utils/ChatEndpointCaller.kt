package utils

import java.lang.Exception

interface ChatEndpointCaller<R> {
    suspend fun call(handler: ResponseCallback<R>) {}
}
interface ChatEndpointCallerWithData<D, R> {
    suspend fun call(data: D?, handler: ResponseCallback<R>) {}
}

interface ResponseCallback<T> {
    fun onResponse(response: T)
    fun onFailure(e: Exception?)
}