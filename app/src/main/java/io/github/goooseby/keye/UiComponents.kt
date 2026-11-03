package io.github.goooseby.keye

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset

@Composable
fun PillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // One animated coordinate drives both the indicator and label colors. The tabs
    // themselves have no independent ripple/elevation that can arrive ahead of it.
    val position by key(labels.size) {animateFloatAsState(selected.toFloat(),tween(240,easing=FastOutSlowInEasing),label="tab position")}
    Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val width=maxWidth/labels.size
            val density=androidx.compose.ui.platform.LocalDensity.current
            val widthPx=with(density) {width.toPx()}
            Box(Modifier.width(width).height(48.dp).graphicsLayer {translationX=widthPx*position}
                .background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(12.dp)))
            Row(Modifier.selectableGroup()) {
                labels.forEachIndexed {index,title ->
                    val interaction=remember {MutableInteractionSource()}
                    val focused by interaction.collectIsFocusedAsState()
                    val amount=(1f-kotlin.math.abs(position-index)).coerceIn(0f,1f)
                    Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                        .border(if(focused) 1.dp else 0.dp,if(focused) MaterialTheme.colorScheme.primary else Color.Transparent,RoundedCornerShape(12.dp))
                        .selectable(index==selected,interactionSource=interaction,indication=null,role=Role.Tab,onClick={if(index!=selected) onSelect(index)}),contentAlignment=Alignment.Center) {
                        Text(title,style=MaterialTheme.typography.labelLarge,color=lerp(MaterialTheme.colorScheme.onSurfaceVariant,MaterialTheme.colorScheme.primary,amount),fontWeight=FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    val focus=LocalFocusManager.current
    TextField(value, onChange, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(28.dp),
        singleLine=true, textStyle=MaterialTheme.typography.bodyMedium,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus()}),
        placeholder={Text(hint, style=MaterialTheme.typography.bodyMedium)},
        leadingIcon={AppIcon("search")},
        trailingIcon={ if(value.isNotEmpty()) IconButton(onClick={onChange("")}) {AppIcon("close", "清除搜索")} },
        colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor=Color.Transparent, unfocusedIndicatorColor=Color.Transparent,
            focusedLeadingIconColor=MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor=MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
fun IconBadge(icon: String, modifier: Modifier = Modifier, tint: Color = featureColor(icon),
              background: Color = tint.copy(alpha=if(isSystemInDarkTheme()) .18f else .10f)) {
    Box(modifier.size(44.dp).background(background, RoundedCornerShape(14.dp)), contentAlignment=Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides tint) { AppIcon(icon) }
    }
}

@Composable
fun StatusPill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(color=color.copy(alpha=.09f), shape=RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal=9.dp, vertical=4.dp), style=MaterialTheme.typography.labelSmall, color=color)
    }
}

@Composable
fun EmptyState(icon: String, title: String, modifier: Modifier = Modifier, detail: String? = null,
               action: String? = null, onAction: () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surface
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=32.dp), horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(144.dp, 124.dp), contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val unit=size.width/144f
                drawCircle(accent.copy(alpha=.045f), 51*unit, center)
                rotate(-12f) { drawRoundRect(line.copy(alpha=.65f), Offset(45*unit,23*unit), Size(62*unit,83*unit), androidx.compose.ui.geometry.CornerRadius(9*unit)) }
                rotate(7f) {
                    drawRoundRect(paper, Offset(43*unit,17*unit), Size(62*unit,83*unit), androidx.compose.ui.geometry.CornerRadius(9*unit))
                    drawRoundRect(line, Offset(43*unit,17*unit), Size(62*unit,83*unit), androidx.compose.ui.geometry.CornerRadius(9*unit), style=Stroke(unit))
                }
                drawCircle(accent.copy(alpha=.16f), 3*unit, Offset(22*unit,40*unit))
                drawCircle(accent.copy(alpha=.10f), 5*unit, Offset(124*unit,89*unit))
            }
            CompositionLocalProvider(LocalContentColor provides accent) {AppIcon(icon, modifier=Modifier.size(30.dp))}
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style=MaterialTheme.typography.titleMedium)
        detail?.let { Spacer(Modifier.height(6.dp)); Text(it, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant) }
        action?.let { Spacer(Modifier.height(24.dp)); Button(onClick=onAction, contentPadding=PaddingValues(horizontal=28.dp, vertical=14.dp)) {Text(it)} }
    }
}

@Composable
fun SheetHeading(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom=12.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style=MaterialTheme.typography.titleLarge)
        IconButton(onClick=onClose) {AppIcon("close", "关闭")}
    }
}

@Composable
fun PreferenceRow(icon: String, title: String, subtitle: String? = null,
                  value: String? = null, onClick: (() -> Unit)? = null,compact: Boolean=false) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier)
        .padding(horizontal=16.dp, vertical=if(compact) 10.dp else 16.dp), verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, Modifier.size(if(compact) 34.dp else 40.dp))
        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(title, style=MaterialTheme.typography.bodyLarge)
            subtitle?.let {Text(it, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        value?.let {Text(it, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(onClick!=null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {AppIcon("next", modifier=Modifier.size(18.dp))}
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style=MaterialTheme.typography.labelLarge, color=MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.padding(start=4.dp, top=12.dp, bottom=4.dp))
}

@Composable
fun EmptyHint(text: String) = EmptyState("document", text)
