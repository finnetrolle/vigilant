package io.vigilant.policy.domain

import java.util.Collections
import java.util.TreeSet

/** Returns an unmodifiable defensive list snapshot of [values]. */
internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Returns an unmodifiable naturally sorted set snapshot of [values]. */
internal fun <T : Comparable<T>> immutableSortedSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(TreeSet(values))
