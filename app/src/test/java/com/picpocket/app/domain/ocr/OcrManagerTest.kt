package com.picpocket.app.domain.ocr

import android.app.Application
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.util.TestBitmapFactory
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class OcrManagerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var store: DocumentStore
    private lateinit var ocrManager: OcrManager
    private var documentId: String = ""

    @Before
    fun setUp() = runTest(coroutineRule.dispatcher) {
        val app = RuntimeEnvironment.getApplication() as Application
        store = DocumentStore(app)
        documentId = store.createDocument("Ocr Doc").getOrThrow().id
        ocrManager = OcrManager(FakeOcrEngine(), store)
    }

    private fun addJpegPage(pageNumber: Int) = runTest(coroutineRule.dispatcher) {
        val bitmap = TestBitmapFactory.allBlack()
        val dir = store.documentDir(documentId)
        dir.mkdirs()
        val file = File(dir, "page_%03d.jpg".format(pageNumber))
        file.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        store.addPage(documentId, pageNumber, file.name, file.length()).getOrThrow()
    }

    @Test
    fun `runOcr writes text and completes document`() = runTest(coroutineRule.dispatcher) {
        addJpegPage(1)
        addJpegPage(2)

        val emissions = mutableListOf<Unit>()
        val job = launch { ocrManager.metadataChanged.collect { emissions.add(it) } }

        ocrManager.runOcr(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        job.cancel()

        val updated = store.readMetadata(documentId).getOrThrow()
        assertTrue("ocrComplete should be true", updated.ocrComplete)
        assertTrue("all pages should have OCR text", updated.pages.all { it.ocrText != null })
        assertTrue("metadataChanged should have been emitted", emissions.isNotEmpty())
    }

    @Test
    fun `runOcr skips already-complete document`() = runTest(coroutineRule.dispatcher) {
        addJpegPage(1)
        store.writeMetadata(documentId, store.readMetadata(documentId).getOrThrow().copy(ocrComplete = true))

        ocrManager.runOcr(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val updated = store.readMetadata(documentId).getOrThrow()
        assertTrue(updated.ocrComplete)
        assertTrue(updated.pages.all { it.ocrText == null })
    }

    @Test
    fun `runOcr tolerates engine failure on one page and still completes others`() = runTest(coroutineRule.dispatcher) {
        addJpegPage(1)
        addJpegPage(2)

        val manager = OcrManager(
            object : OcrEngine {
                var calls = 0
                override suspend fun recognize(bitmap: android.graphics.Bitmap): OcrResult {
                    calls++
                    if (calls == 1) throw RuntimeException("engine down")
                    return OcrResult("recovered text", 0.9f)
                }
            },
            store,
        )

        manager.runOcr(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val updated = store.readMetadata(documentId).getOrThrow()
        val page1 = updated.pages.first { it.pageNumber == 1 }
        val page2 = updated.pages.first { it.pageNumber == 2 }
        assertEquals("failed page should be marked done with empty text", "", page1.ocrText)
        assertEquals("recovered text", page2.ocrText)
        assertTrue("ocrComplete should be true when all pages processed", updated.ocrComplete)
    }
}