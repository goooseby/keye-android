package io.github.goooseby.keye.ui.reader

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import io.github.goooseby.keye.data.StudyMaterial
import java.io.File
import kotlin.math.abs

/** A single document viewport. Page heights grow together; vertical scrolling stays lazy. */
@Composable
internal fun ContinuousReader(material: StudyMaterial,indices: List<Int>,list: LazyListState,
    mediaPath: (String)->File,resetToken: Int,onTap: ()->Unit,onZoomed: (Boolean)->Unit) {
    var scale by remember(material.id) {mutableFloatStateOf(1f)}
    var horizontal by remember(material.id) {mutableFloatStateOf(0f)}
    val density=LocalDensity.current
    val tap by rememberUpdatedState(onTap)
    LaunchedEffect(resetToken) {scale=1f;horizontal=0f}
    LaunchedEffect(scale) {onZoomed(scale>1.01f)}
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val documentWidth=maxWidth
        val viewport=with(density) {documentWidth.toPx()}
        fun transform(zoom: Float,panX: Float,panY: Float,focusX: Float,focusY: Float) {
            val old=scale
            val next=(old*zoom).coerceIn(1f,5f)
            val ratio=next/old
            val index=list.firstVisibleItemIndex
            val offset=list.firstVisibleItemScrollOffset
            scale=next
            horizontal=(horizontal*ratio+panX+(viewport/2-focusX)*(ratio-1)).coerceIn(-viewport*(next-1)/2,viewport*(next-1)/2)
            if(abs(next-old)>.001f || abs(panY)>.01f) {
                // Schedule positioning in the same measure pass as the new width.
                // No competing per-frame scroll coroutines during a pinch.
                list.requestScrollToItem(index,(offset*ratio+focusY*(ratio-1)-panY).toInt().coerceAtLeast(0))
            }
        }
        Box(Modifier.fillMaxSize()
            .pointerInput(material.id,viewport) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed=false)
                    var pinching=false
                    do {
                        val event=awaitPointerEvent()
                        val fingers=event.changes.count {it.pressed}
                        if(fingers>=2) {
                            pinching=true
                            val pan=event.calculatePan()
                            val focus=event.calculateCentroid()
                            transform(event.calculateZoom(),pan.x,pan.y,focus.x,focus.y)
                            event.changes.forEach {it.consume()}
                        } else if(!pinching && scale>1.01f) {
                            val pan=event.calculatePan()
                            horizontal=(horizontal+pan.x).coerceIn(-viewport*(scale-1)/2,viewport*(scale-1)/2)
                            // Do not consume: the lazy column handles vertical movement normally.
                        }
                    } while(event.changes.any {it.pressed})
                }
            }
            .pointerInput(material.id,viewport) {detectTapGestures(onTap={tap()},onDoubleTap={point ->
                transform(if(scale>1.01f) 1f/scale else 2.5f,0f,0f,point.x,point.y)
            })}) {
            LazyColumn(state=list,modifier=Modifier.requiredWidth(documentWidth*scale).fillMaxHeight()
                .graphicsLayer {translationX=horizontal}) {
                items(indices,key={material.pages[it].source+":"+material.pages[it].pdfPage}) {original ->
                    ReaderCanvas(material.pages[original],mediaPath,Modifier.fillMaxWidth(),original+1,
                        continuous=true,active=false,resetToken=0,onTap={},onZoomed={},
                        gesturesEnabled=false,highResolution=scale>1.01f)
                }
            }
        }
    }
}
