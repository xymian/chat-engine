package utils

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

fun CoroutineScope.runLockingTask(mutex: Mutex, lockingTask: suspend () -> Unit) {
    launch(Dispatchers.Default) {
        mutex.withLock {
            lockingTask()
        }
    }
}

fun CoroutineScope.runInBackground(backgroundTask: suspend () -> Unit) {
    launch(Dispatchers.IO) {
        backgroundTask()
    }
}

fun CoroutineScope.runOnMainThread(task: suspend () -> Unit) {
    launch(Dispatchers.Main) {
        task()
    }
}