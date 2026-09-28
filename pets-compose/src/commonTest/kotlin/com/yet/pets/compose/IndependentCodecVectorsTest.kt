package com.yet.pets.compose

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Independent Pillow/libwebp vectors, executed on JVM and iOS simulator. */
class IndependentCodecVectorsTest {
    private fun ready(b64: String, width: Int, height: Int) {
        val decoded = decodeAtlasBytes(Base64.decode(b64))
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
    }

    @Test fun png() = ready("iVBORw0KGgoAAAANSUhEUgAAAAwAAAAKCAIAAAAPTiitAAAAFklEQVR4nGM8oWHDQAgwEVQxqohoRQC/wQFASnD1sAAAAABJRU5ErkJggg==", 12, 10)

    @Test fun jpeg() = ready("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/2wBDAQMDAwQDBAgEBAgQCwkLEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBD/wAARCAAMABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDxqiiivkj+0D//2Q==", 16, 12)

    @Test fun staticGif() = ready("R0lGODdhDgALAIEAAP8AAAAAAAAAAAAAACwAAAAADgALAAAIFwABCBxIsKDBgwgTKlzIsKHDhxAjAggIADs=", 14, 11)

    @Test fun vp8Lossy() = ready("UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoQAAwAAMASJaACdLoB+AADsAD+9uYH/izkYjs83/7PL+zy/s8v/s2AAAA=", 16, 12)

    @Test fun vp8lLossless() = ready("UklGRh4AAABXRUJQVlA4TBEAAAAvD8ACAAdQlCKXp/+BiOh/AAA=", 16, 12)

    @Test fun vp8xAlpha() = ready("UklGRmgAAABXRUJQVlA4WAoAAAAQAAAADwAACwAAQUxQSA8AAAABDzD/ERHiVaoR/Y+ZmRkAVlA4IDIAAAAQAgCdASoQAAwAAMASJaACdLoB+AH6AAPIAP7wUx//mudBpyX/Ndv/2MmoOAZf9iPAAA==", 16, 12)

    @Test fun animatedWebpRejectedBeforeDecode() {
        val bytes = Base64.decode("UklGRsQAAABXRUJQVlA4WAoAAAACAAAADwAACwAAQU5JTQYAAAAAAAAAAABBTk1GSgAAAAAAAAAAAA8AAAsAAMgAAAJWUDggMgAAADABAJ0BKhAADAABQCYloAADcAD+8ut///mwP/bz/wR6Af//0uD//pcH//S4P/SkAAAAQU5NRkYAAAAAAAAAAAAPAAALAADIAAAAVlA4IC4AAAA0AQCdASoQAAwAAAAmJaAAA3AA/vtV4///S4P/+lwf/9Lg/9Lg//rV5Vesq6AA")
        val decoded = decodeAtlasBytes(bytes)
        assertIs<PetAtlasState.Failed>(decoded.state)
        assertNull(decoded.bitmap)
    }
}
