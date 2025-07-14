package utils

import models.ComparableMessage
import java.util.PriorityQueue

fun <M: ComparableMessage> MutableSet<M>.empty(): List<M> {
    val messages = mutableListOf<M>()
    messages.addAll(this)
    this.clear()
    return messages
}

inline fun <reified T> cast(instance: Any): T {
    return (instance as? T) ?: throw ClassCastException(
        "${instance::class.java.simpleName} could not be cast to ${T::class.java.simpleName}")
}