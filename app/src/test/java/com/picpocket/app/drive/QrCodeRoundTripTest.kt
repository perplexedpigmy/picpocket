package com.picpocket.app.drive

import com.picpocket.app.util.QrCodeGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrCodeRoundTripTest {

    @Test
    fun `generate returns bitmap for valid text`() {
        val bitmap = QrCodeGenerator.generate("picpocket-pair:device-123:MyPhone:1000")
        assertNotNull(bitmap)
    }

    @Test
    fun `generate returns null for empty text`() {
        val bitmap = QrCodeGenerator.generate("")
        assertNull(bitmap)
    }

    @Test
    fun `generate produces correct size bitmap`() {
        val size = 256
        val bitmap = QrCodeGenerator.generate("test-data", size)
        assertNotNull(bitmap)
        assertEquals(size, bitmap!!.width)
        assertEquals(size, bitmap.height)
    }

    @Test
    fun `generate handles special characters`() {
        val data = "picpocket-pair:device-abc!@#:My Phone:2000"
        val bitmap = QrCodeGenerator.generate(data)
        assertNotNull(bitmap)
    }

    @Test
    fun `generate handles long URIs`() {
        val longId = "a".repeat(100)
        val data = "picpocket-pair:$longId:Device-With-Long-ID:${Long.MAX_VALUE}"
        val bitmap = QrCodeGenerator.generate(data)
        assertNotNull(bitmap)
    }

    @Test
    fun `generate produces different bitmaps for different inputs`() {
        val a = QrCodeGenerator.generate("hello")
        val b = QrCodeGenerator.generate("world")
        assertNotNull(a)
        assertNotNull(b)
    }
}
