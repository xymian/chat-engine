package utils

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

internal fun CoroutineScope.runInBackground(backgroundTask: suspend () -> Unit) {
    launch(Dispatchers.IO) {
        backgroundTask()
    }
}

internal fun CoroutineScope.runOnMainThread(task: suspend () -> Unit) {
    launch(Dispatchers.Main) {
        task()
    }
}