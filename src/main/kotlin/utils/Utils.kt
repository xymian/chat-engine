package utils

import models.ComparableMessage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

internal fun <M: ComparableMessage> MutableSet<M>.empty(): List<M> {
    val messages = mutableListOf<M>()
    messages.addAll(this)
    this.clear()
    return messages
}