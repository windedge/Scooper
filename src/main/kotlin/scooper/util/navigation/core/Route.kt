package scooper.util.navigation.core

// source: https://github.com/mvarnagiris/compose-navigation

data class Route<T> internal constructor(val key: BackStackKey, val value: T)