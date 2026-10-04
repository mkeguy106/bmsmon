package dev.joely.bmsmon

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.joely.bmsmon.data.removeIfEquals
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DATA-18: a config enqueued while the previous push was in flight must survive the clear. */
class PrefsCasTest {
    private val key = stringPreferencesKey("pending_temp_config")

    private fun withStore(block: suspend (DataStore<Preferences>) -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File.createTempFile("cas", ".preferences_pb").apply { delete(); deleteOnExit() }
        try {
            runBlocking { block(PreferenceDataStoreFactory.create(scope = scope) { file }) }
        } finally {
            scope.cancel()
        }
    }

    @Test fun aNewerConfigSurvivesClearingTheOneThatWasSent() = withStore { ds ->
        ds.edit { it[key] = "A" }
        ds.edit { it[key] = "B" }
        ds.edit { it.removeIfEquals(key, "A") }
        assertEquals("B", ds.data.first()[key])
    }

    @Test fun theSentConfigIsClearedWhenNothingNewerArrived() = withStore { ds ->
        ds.edit { it[key] = "A" }
        ds.edit { it.removeIfEquals(key, "A") }
        assertNull(ds.data.first()[key])
    }
}
