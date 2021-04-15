package scooper.framework.navigation.core

data class Route<T> internal constructor(val key: BackStackKey, val value: T)