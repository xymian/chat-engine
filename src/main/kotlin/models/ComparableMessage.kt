package models

import kotlinx.serialization.Serializable

@Serializable
abstract class ComparableMessage(
): Comparable<String> {
    abstract val timestamp: String?
    abstract val sender: String?
    abstract val message: Any?

    override fun compareTo(other: String): Int {
        return timestamp?.compareTo(other) ?: throw IllegalArgumentException("timestamp cannot be null")
    }
}
