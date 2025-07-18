package utils

import models.ComparableMessage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

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

fun Date.toISOString(): String {
    return DateTimeFormatter.ofPattern(
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    ).withZone(ZoneId.systemDefault()).format(this.toInstant())
}

fun Date.toFormat(pattern: String): String {
    return DateTimeFormatter.ofPattern(
        pattern
    ).withZone(ZoneId.systemDefault()).format(this.toInstant())
}