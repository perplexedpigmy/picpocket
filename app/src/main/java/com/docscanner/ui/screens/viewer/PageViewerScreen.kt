package com.docscanner.ui.screens.viewer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.docscanner.data.model.Page
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PageViewerScreen(
    documentId: Long,
    initialPageIndex: Int,
    onNavigateBack: () -> Unit,
    viewModel: PageViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(documentId) {
        viewModel.loadPages(documentId, initialPageIndex)
    }

    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { state.pages.size },
    )

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setPageIndex(pagerState.currentPage)
    }

    var showControls by remember { mutableStateOf(true) }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    Scaffold(
        topBar = {
            if (showControls) {
                TopAppBar(
                    title = { Text("${pagerState.currentPage + 1} / ${state.pages.size}") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showControls) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${state.pages.size}",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { pageIndex ->
            val page = state.pages.getOrNull(pageIndex) ?: return@HorizontalPager
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(showControls) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val up = waitForUpOrCancellation()
                            if (up != null) showControls = true
                        }
                    },
            ) {
                ZoomablePage(page = page)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePage(page: Page) {
    val bitmap = remember(page.imageUri) {
        val path = Uri.parse(page.imageUri).path
        if (path != null) {
            try {
                BitmapFactory.decodeFile(path)
            } catch (_: Exception) { null }
        } else null
    }

    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Failed to load page", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var wasTap = true
                    val downTime = System.currentTimeMillis()

                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.fold(true) { a, c -> a && c.isConsumed }) break

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val isMultiTouch = event.changes.size > 1

                        if (isMultiTouch || scale > 1f) {
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            val fitScale = minOf(
                                containerSize.width.toFloat() / bitmap.width,
                                containerSize.height.toFloat() / bitmap.height,
                            )
                            val displayedWidth = bitmap.width * fitScale
                            val displayedHeight = bitmap.height * fitScale
                            val maxPanX = ((scale - 1f) * displayedWidth / 2f).coerceAtLeast(0f)
                            val maxPanY = ((scale - 1f) * displayedHeight / 2f).coerceAtLeast(0f)
                            offsetX = (offsetX + panChange.x).coerceIn(-maxPanX, maxPanX)
                            offsetY = (offsetY + panChange.y).coerceIn(-maxPanY, maxPanY)
                            event.changes.forEach { change ->
                                if (change.position != change.previousPosition) change.consume()
                            }
                        }

                        if (isMultiTouch || event.changes.any { (it.position - it.previousPosition).getDistance() > 20f }) {
                            wasTap = false
                        }
                    } while (event.changes.any { it.pressed })

                    if (wasTap && System.currentTimeMillis() - downTime < 300L) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 400L) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }
                        lastTapTime = now
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
                clip = false
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Page ${page.pageNumber}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
