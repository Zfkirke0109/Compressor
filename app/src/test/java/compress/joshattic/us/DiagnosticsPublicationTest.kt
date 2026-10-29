package compress.joshattic.us

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiagnosticsPublicationTest {
    @Test fun publishHappensAfterTheStreamHasClosed() {
        val events = mutableListOf<String>()
        val result = DiagnosticsPublication.writeAndPublish(
            write = { events += "write-and-close" },
            publish = { events += "publish"; 1 },
            delete = { events += "delete" }
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf("write-and-close", "publish"), events)
    }

    @Test fun providerPublishFailuresDeleteThePendingRowAndNeverReportSuccess() {
        for (rows in listOf(0, 2)) {
            var deleted = false
            val result = DiagnosticsPublication.writeAndPublish({}, { rows }, { deleted = true })
            assertTrue(result.isFailure)
            assertTrue(deleted)
        }
        val problem = IOException("provider disconnected")
        var deleted = false
        val result = DiagnosticsPublication.writeAndPublish({}, { throw problem }, { deleted = true })
        assertSame(problem, result.exceptionOrNull())
        assertTrue(deleted)
    }

    @Test fun aPartialWriteIsNeverPublishedEvenWhenCleanupFails() {
        val problem = IOException("storage full")
        var published = false
        var deleted = false
        val result = DiagnosticsPublication.writeAndPublish(
            { throw problem }, { published = true; 1 }, { deleted = true; error("offline") }
        )
        assertSame(problem, result.exceptionOrNull())
        assertFalse(published)
        assertTrue(deleted)
    }
}
