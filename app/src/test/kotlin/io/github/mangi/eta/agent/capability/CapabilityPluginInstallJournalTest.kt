package io.github.mangi.eta.agent.capability

import android.app.Application
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class CapabilityPluginInstallJournalTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        File(context.filesDir, "capability-center/plugin-install-journal.json").delete()
    }

    @Test
    fun writesOnlyTransactionIdentifiersAndClearsAtomically() {
        val store = CapabilityPluginInstallJournal(context)
        val transaction = CapabilityPluginInstallTransaction(
            pluginName = "Office tools",
            version = "1.2.0",
            pluginRecordId = "plugin-test",
            skillIds = listOf("excel-helper"),
            apiConnectorIds = listOf("api-one"),
            mcpServerIds = listOf("mcp-one"),
        )

        store.write(transaction)
        assertEquals(transaction, store.load())
        val raw = File(context.filesDir, "capability-center/plugin-install-journal.json").readText()
        assertEquals(false, raw.contains("Bearer"))
        store.clear()
        assertNull(store.load())
    }
}
