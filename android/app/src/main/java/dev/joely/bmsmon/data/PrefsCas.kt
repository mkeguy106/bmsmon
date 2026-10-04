package dev.joely.bmsmon.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * Remove [key] only if it still holds [expected]: compare-and-clear inside one DataStore edit
 * (DATA-18). The pending config push used to be cleared unconditionally after its POST, deleting a
 * newer config enqueued while that POST was in flight. Returns whether it removed.
 */
internal fun <T> MutablePreferences.removeIfEquals(key: Preferences.Key<T>, expected: T): Boolean {
    if (this[key] != expected) return false
    remove(key)
    return true
}
