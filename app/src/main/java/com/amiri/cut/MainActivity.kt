package com.amiri.cut

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.amiri.cut.ui.editor.EditorScreen
import com.amiri.cut.ui.home.HomeScreen
import com.amiri.cut.ui.newproject.NewProjectScreen
import com.amiri.cut.ui.settings.SettingsScreen
import com.amiri.cut.ui.theme.AmiriTheme
import com.amiri.cut.ui.theme.Haptics

sealed interface Screen {
    data object Home : Screen
    data object NewProject : Screen
    data object Settings : Screen
    data class Editor(val projectId: String, val importUris: List<Uri> = emptyList(), val recover: Boolean = false) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AmiriCutApp
        setContent {
            val settings = app.settings
            LaunchedEffect(settings.haptics) { Haptics.enabled = settings.haptics }
            AmiriTheme(accent = Color(settings.accent.argb)) {
                AppNavigation(app)
            }
        }
    }
}

@Composable
private fun AppNavigation(app: AmiriCutApp) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack.removeAt(stack.lastIndex); stack.add(s) }

    // The editor handles its own back press (it must save first).
    BackHandler(enabled = stack.size > 1 && current !is Screen.Editor) { pop() }

    AnimatedContent(
        targetState = current,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        contentKey = { s -> if (s is Screen.Editor) "editor:" + s.projectId else s.toString() },
        label = "nav",
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(
                app = app,
                onNewProject = { push(Screen.NewProject) },
                onOpen = { id, recover -> push(Screen.Editor(id, recover = recover)) },
                onSettings = { push(Screen.Settings) },
            )
            Screen.NewProject -> NewProjectScreen(
                app = app,
                onBack = { pop() },
                onCreated = { id, uris -> replace(Screen.Editor(id, importUris = uris)) },
            )
            Screen.Settings -> SettingsScreen(app = app, onBack = { pop() })
            is Screen.Editor -> EditorScreen(
                app = app,
                projectId = screen.projectId,
                importUris = screen.importUris,
                recover = screen.recover,
                onExit = { pop() },
            )
        }
    }
}
