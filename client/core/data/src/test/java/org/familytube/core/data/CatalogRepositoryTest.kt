package org.familytube.core.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRepositoryTest {
    private val origin = "http://192.168.1.10:8000".toHttpUrl()

    @Test fun preservesUnknownDurationAndRejectsOffOriginStreams() {
        val video = VideoDto("a", "Family clip", durationSeconds = 0.0,
            streamUrl = "http://192.168.1.10:8000/media/a",
            thumbnailUrl = "http://other-host/image.png")
        val mapped = video.toVideoAt(origin)!!
        assertEquals(0.0, mapped.durationSeconds, 0.0)
        assertNull(mapped.thumbnailUrl)
        assertNull(video.copy(streamUrl = "http://other-host/media/a").toVideoAt(origin))
    }
}
