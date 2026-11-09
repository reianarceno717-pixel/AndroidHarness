package com.androidharness.app.ui.bubble

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.data.ConversationKind

/**
 * The floating window content: exactly the app's native GOD-AI chat, in the
 * theme the app uses. The drag handle above the chat moves the window so it
 * behaves like Messenger's floating heads; the close button dismisses it.
 */
@Composable
internal fun OverlayContent(
    container: AppContainer,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val settingsState by container.settings.settings.collectAsState(null)
    val settings = settingsState ?: return
    HarnessTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
        progressBarStyle = settings.progressBarStyle,
        colorPalette = settings.colorPalette,
        customPalette = settings.customPalettes.firstOrNull { it.id == settings.activeCustomPaletteId }
            .takeUnless { settings.dynamicColor || settings.harnessCanvas.adaptColors },
        glassEnabled = settings.harnessGlassEnabled,
        liquidGlass = settings.harnessLiquidGlass,
        glassTransparency = settings.harnessGlassTransparency,
    ) {
        val surface = MaterialTheme.colorScheme.surface
        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(surface)
                .imePadding(),
        ) {
            // Window handle: slim grab bar for moving, close button for dismissing.
            Box(Modifier.fillMaxWidth().wrapContentHeight()) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .padding(top = 6.dp)
                        .width(44.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(onDrag) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                onDrag(amount.x, amount.y)
                            }
                        },
                )
                IconButton(
                    onClick = { onClose() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .size(34.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close GOD-AI window",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // The ViewTreeViewModelStoreOwner set on the ComposeView (= the overlay
            // service's store) serves this viewModel, so state survives recomposition.
            val bubbleViewModel: ChatViewModel =
                viewModel(
                    factory = ChatViewModel.factory(
                        container = container,
                        sessionId = null,
                        kind = ConversationKind.CODE,
                    ),
                )
            ChatScreen(
                viewModel = bubbleViewModel,
                onOpenDrawer = {},
                onOpenFile = { _, _ -> onClose() },
                onNewChat = {},
                onOpenTerminal = {},
                onOpenFiles = {},
                onOpenSubagent = { _ -> },
                onOpenSettings = {},
                onNavigateToSession = {},
                onSelectSection = {},
            )
        }
    }
}
