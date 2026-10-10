package com.kadir.linkdnm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/** No YouTube requests: test actual Android MediaStore save, list, open and delete. */
@RunWith(AndroidJUnit4::class)
class Mp3StorageInstrumentedTest {
    @Test
    fun downloadedMp3AppearsInSameScreenLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = Mp3Storage(context)
        val bytes = "ID3-test-audio-data-from-instrumentation".toByteArray()
        val name = Mp3Options.outputName("dQw4w9WgXcQ", 192, System.currentTimeMillis())
        val saved = storage.save(
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
            name,
            { false },
            { _, _ -> }
        )
        try {
            val tracks = storage.list()
            assertTrue("Just-downloaded MP3 must be shown immediately",
                tracks.any { it.uri == saved.uri && it.name == name })
            val content = context.contentResolver.openInputStream(saved.uri)
                ?.use { it.readBytes() } ?: byteArrayOf()
            assertArrayEquals(bytes, content)
        } finally {
            assertTrue("Test MP3 should be deletable", storage.delete(saved))
        }
        assertFalse("Deleted MP3 must disappear", storage.list().any { it.uri == saved.uri })
    }

    @Test
    fun videoToMp3AppearsInSameScreenLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = Mp3Storage(context)
        val bytes = "ID3-local-video-fallback-data".toByteArray()
        val name = "video-yerelmedya0-192k-" + System.currentTimeMillis() + ".mp3"
        val saved = storage.save(
            ByteArrayInputStream(bytes), bytes.size.toLong(), name,
            { false }, { _, _ -> }
        )
        try {
            assertTrue(
                "MP3 converted from phone media must be visible",
                storage.list().any { it.name == name && it.uri == saved.uri }
            )
            val actual = context.contentResolver.openInputStream(saved.uri)
                ?.use { it.readBytes() } ?: byteArrayOf()
            assertArrayEquals(bytes, actual)
        } finally {
            assertTrue(storage.delete(saved))
        }
        assertFalse(storage.list().any { it.uri == saved.uri })
    }

}

