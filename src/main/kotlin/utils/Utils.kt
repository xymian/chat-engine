package utils

inline fun <reified T> cast(instance: Any): T {
    return (instance as? T) ?: throw ClassCastException(
        "${instance::class.java.simpleName} could not be cast to ${T::class.java.simpleName}")
}