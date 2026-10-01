package org.familytube.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerSettingsRepositoryTest {
    @Test fun normalizesOneOrigin() {
        assertEquals("http://192.168.1.10:8000", normalizeServerUrl("  http://192.168.1.10:8000/ "))
    }

    @Test fun rejectsAddressesThatCanEscapeTheConfiguredOrigin() {
        for (input in listOf(
            "http://user:pass@192.168.1.10:8000",
            "http://192.168.1.10:8000/api/health",
            "http://192.168.1.10:8000?next=other",
            "http://192.168.1.10:0",
            "file:///home/video.mp4",
        )) {
            assertThrows(input, IllegalArgumentException::class.java) { normalizeServerUrl(input) }
        }
    }
}
