package utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun runLockingTask(mutex: Mutex, lockingTask: suspend () -> Unit) {
    runBlocking {
        launch(Dispatchers.Default) {
            mutex.withLock {
                lockingTask()
            }
        }
    }
}

fun runInBackground(backgroundTask: suspend () -> Unit) {
    runBlocking {
        launch(Dispatchers.Default) {
            backgroundTask()
        }
    }
}

fun runOnMainThread(task: () -> Unit) {
    runBlocking {
        launch(Dispatchers.Main) {
            task()
        }
    }
}