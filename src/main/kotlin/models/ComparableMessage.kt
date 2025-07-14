package models

import kotlinx.serialization.Serializable

@Serializable
abstract class ComparableMessage(
): Comparable<ComparableMessage> {
    abstract val timestamp: String
    abstract val sender: String
    abstract val message: Any

    override fun compareTo(other: ComparableMessage): Int {
        return timestamp.compareTo(other.timestamp)
    }
}
