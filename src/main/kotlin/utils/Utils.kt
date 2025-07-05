package utils

import models.ComparableMessage
import java.util.PriorityQueue

fun <M: ComparableMessage> PriorityQueue<M>.emptyQueue(): List<M> {
    val messages = mutableListOf<M>()
    while (isNotEmpty()) {
        messages.add(poll())
    }
    return messages
}

inline fun <reified T> cast(instance: Any): T {
    return (instance as? T) ?: throw ClassCastException(
        "${instance::class.java.simpleName} could not be cast to ${T::class.java.simpleName}")
}