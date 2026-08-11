package com.picpocket.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PageNamingTest {

    @Test
    fun `filename is sha256 hex plus jpg extension`() {
        val name = PageNaming.filenameFor("hello".toByteArray())
        val hexPart = name.removeSuffix(".jpg")
        assertEquals(64, hexPart.length)
        assertTrue(name.endsWith(".jpg"))
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("hello".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("$expected.jpg", name)
    }

    @Test
    fun `identical bytes produce identical filenames`() {
        val a = PageNaming.filenameFor("same-content".toByteArray())
        val b = PageNaming.filenameFor("same-content".toByteArray())
        assertEquals(a, b)
    }

    @Test
    fun `different bytes produce different filenames`() {
        val a = PageNaming.filenameFor("one".toByteArray())
        val b = PageNaming.filenameFor("two".toByteArray())
        assertNotEquals(a, b)
    }

    @Test
    fun `file overload matches byte overload`() {
        val f = File.createTempFile("page", ".jpg")
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(PageNaming.filenameFor(byteArrayOf(1, 2, 3, 4, 5)), PageNaming.filenameFor(f))
    }
}

class MetadataNamingTest {

    @Test
    fun `name renders version and passphrase`() {
        assertEquals("metadata.0.0.json", MetadataNaming.name(0, 0))
        assertEquals("metadata.12.3.json", MetadataNaming.name(12, 3))
    }

    @Test
    fun `parse round-trips name`() {
        assertEquals(12 to 3, MetadataNaming.parse("metadata.12.3.json"))
        assertEquals(0 to 0, MetadataNaming.parse("metadata.0.0.json"))
    }

    @Test
    fun `parse rejects foreign names`() {
        assertEquals(null, MetadataNaming.parse("metadata.json"))
        assertEquals(null, MetadataNaming.parse("abc123.jpg"))
        assertEquals(null, MetadataNaming.parse(".deleted"))
        assertEquals(null, MetadataNaming.parse("metadata.12.json"))
        assertEquals(null, MetadataNaming.parse("metadata.12.3"))
    }

    @Test
    fun `isMetadata recognizes only versioned names`() {
        assertTrue(MetadataNaming.isMetadata("metadata.5.1.json"))
        assertTrue(!MetadataNaming.isMetadata("metadata.json"))
        assertTrue(!MetadataNaming.isMetadata("abc123.jpg"))
    }
}
