package io.github.goooseby.keye.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalPageActive = compositionLocalOf {true}

/** Outgoing pages remain drawable, but immediately yield input and foreground work. */
@Composable
internal fun PageContent(active: Boolean, content: @Composable () -> Unit) {
    val parent=LocalLifecycleOwner.current
    val foreground=active && LocalPageActive.current
    val latestForeground by rememberUpdatedState(foreground)
    val owner=remember(parent) {PageLifecycleOwner()}
    DisposableEffect(parent,owner) {
        val observer=LifecycleEventObserver {_,_ ->owner.update(parent.lifecycle.currentState,latestForeground)}
        parent.lifecycle.addObserver(observer)
        owner.update(parent.lifecycle.currentState,latestForeground)
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.update(Lifecycle.State.DESTROYED,false)
        }
    }
    SideEffect {owner.update(parent.lifecycle.currentState,foreground)}
    CompositionLocalProvider(LocalLifecycleOwner provides owner,LocalPageActive provides foreground) {
        val input=if(foreground) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {
                while(true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach {it.consume()}
            }
        }
        Box(Modifier.fillMaxSize().then(input)) {content()}
    }
}

private class PageLifecycleOwner: LifecycleOwner {
    private val registry=LifecycleRegistry(this)
    override val lifecycle: Lifecycle get()=registry

    fun update(parent: Lifecycle.State, active: Boolean) {
        registry.currentState=if(active) parent else minOf(parent,Lifecycle.State.CREATED)
    }
}
