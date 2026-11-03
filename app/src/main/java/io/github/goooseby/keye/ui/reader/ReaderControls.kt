package io.github.goooseby.keye.ui.reader

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.SectionLabel
import io.github.goooseby.keye.ui.theme.featureColor
import kotlin.math.roundToInt

/** Controls stay above the page: showing them never changes the slide viewport. */
@Composable
fun FullscreenReaderControls(
    visible: Boolean, title: String, position: Int, originalPage: Int, totalPages: Int,
    retainedCount: Int, bookmarked: Boolean, zoomed: Boolean,
    onExit: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (Int) -> Unit, onScrubbing: (Boolean) -> Unit,
    onBookmark: () -> Unit, onReset: () -> Unit, onSavePage: () -> Unit,saveBusy: Boolean=false
) {
    var preview by remember {mutableStateOf<Float?>(null)}
    // A cancelled gesture (including rotation or hiding the controls) must not
    // leave the parent's auto-hide timer permanently suspended.
    DisposableEffect(Unit) {onDispose {onScrubbing(false)}}
    AnimatedVisibility(visible,enter=fadeIn(),exit=fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.72f),Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.displayCutout).padding(start=12.dp,end=20.dp,top=8.dp,bottom=28.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onExit) {AppIcon("back","退出全屏")}
                    Text(title,Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick=onSavePage,enabled=!saveBusy) {if(saveBusy) CircularProgressIndicator(Modifier.size(22.dp),color=Color.White,strokeWidth=2.dp) else AppIcon("download","保存当页图片")}
                    IconToggleButton(checked=bookmarked,onCheckedChange={onBookmark()}) {
                        CompositionLocalProvider(LocalContentColor provides if(bookmarked) Color(0xFF8ED8FF) else Color.White) {AppIcon("bookmark",if(bookmarked) "移除书签" else "添加书签")}
                    }
                    IconButton(onClick=onReset,enabled=zoomed,colors=IconButtonDefaults.iconButtonColors(contentColor=Color.White,disabledContentColor=Color.White.copy(alpha=.4f))) {AppIcon("fit","还原缩放")}
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f))))
                    .windowInsetsPadding(WindowInsets.displayCutout).padding(start=20.dp,end=20.dp,top=28.dp,bottom=12.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FilledIconButton(onClick=onPrevious,enabled=position>0,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White.copy(alpha=.16f),contentColor=Color.White,disabledContainerColor=Color.White.copy(alpha=.06f),disabledContentColor=Color.White.copy(alpha=.3f))) {AppIcon("previous","上一页")}
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("第 $originalPage / $totalPages 页",style=MaterialTheme.typography.labelMedium)
                            Text(if(preview!=null) "定位第 ${preview!!.roundToInt()+1} 张" else "${position+1} / $retainedCount 张",style=MaterialTheme.typography.labelMedium,color=Color.White.copy(alpha=.75f))
                        }
                        Slider(value=preview?:position.toFloat(),onValueChange={preview=it;onScrubbing(true)},
                            onValueChangeFinished={preview?.let {onSeek(it.roundToInt())};preview=null;onScrubbing(false)},
                            valueRange=0f..(retainedCount-1).coerceAtLeast(1).toFloat(),enabled=retainedCount>1,
                            modifier=Modifier.fillMaxWidth().height(48.dp).semantics {contentDescription="阅读进度，当前为第 ${position+1} 张，共 $retainedCount 张"},colors=SliderDefaults.colors(thumbColor=Color(0xFF8ED8FF),activeTrackColor=Color(0xFF8ED8FF),inactiveTrackColor=Color.White.copy(alpha=.26f)))
                    }
                    FilledIconButton(onClick=onNext,enabled=position<retainedCount-1,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White.copy(alpha=.16f),contentColor=Color.White,disabledContainerColor=Color.White.copy(alpha=.06f),disabledContentColor=Color.White.copy(alpha=.3f))) {AppIcon("next","下一页")}
                }
            }
        }
    }
}

internal data class ReaderTool(val icon: String,val title: String,val enabled: Boolean=true,val action: () -> Unit)

@Composable
internal fun ReaderToolGroup(title: String,tools: List<ReaderTool>) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(6.dp)) {
                tools.chunked(2).forEach {row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach {tool ->
                            TextButton(onClick=tool.action,enabled=tool.enabled,modifier=Modifier.weight(1f).heightIn(min=60.dp),shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(10.dp)) {
                                CompositionLocalProvider(LocalContentColor provides if(tool.enabled) featureColor(tool.icon) else MaterialTheme.colorScheme.onSurface.copy(alpha=.38f)) {AppIcon(tool.icon,modifier=Modifier.size(22.dp))}
                                Spacer(Modifier.width(10.dp))
                                Text(tool.title,style=MaterialTheme.typography.labelLarge)
                            }
                        }
                        if(row.size==1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
