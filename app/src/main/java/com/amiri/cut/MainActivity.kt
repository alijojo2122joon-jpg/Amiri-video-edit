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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import com.amiri.cut.ui.editor.EditorScreen
import com.amiri.cut.ui.home.HomeScreen
import com.amiri.cut.ui.newproject.NewProjectScreen
import com.amiri.cut.ui.settings.SettingsScreen
import com.amiri.cut.ui.theme.AmiriTheme
import com.amiri.cut.ui.theme.Haptics

sealed interface Screen {
    data object Splash : Screen
    data object Home : Screen
    data object NewProject : Screen
    data object Settings : Screen
    /** Gallery picker that starts a new project (optionally running a quick tool after import). */
    data class Picker(val quick: com.amiri.cut.ui.editor.QuickStart? = null) : Screen
    data class Editor(
        val projectId: String,
        val importUris: List<Uri> = emptyList(),
        val recover: Boolean = false,
        val quick: com.amiri.cut.ui.editor.QuickStart? = null,
        /** CI screenshot harness only. */
        val demo: com.amiri.cut.export.UiDemo.Plan? = null,
    ) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AmiriCutApp
        if (intent?.getBooleanExtra("amiri_selftest", false) == true) com.amiri.cut.export.SelfTest.run(this)
        val demoScreen = intent?.getStringExtra("amiri_screen")
        val demoScan = intent?.getStringExtra("amiri_scan")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        if (demoScreen != null) { com.amiri.cut.ui.theme.CatSounds.purrOn = false; com.amiri.cut.ui.common.WalkingCatGate.disabled = true }
        setContent {
            val settings = app.settings
            LaunchedEffect(settings.haptics) { Haptics.enabled = settings.haptics; com.amiri.cut.ui.theme.CatSounds.hapticsOn = settings.haptics }
            LaunchedEffect(settings.catSounds, settings.purr) {
                com.amiri.cut.ui.theme.CatSounds.soundsOn = settings.catSounds
                com.amiri.cut.ui.theme.CatSounds.purrOn = settings.purr
                if (!settings.purr || !settings.catSounds) com.amiri.cut.ui.theme.CatSounds.stopPurr()
            }
            AmiriTheme(accent = Color(settings.accent.argb)) {
                com.amiri.cut.ui.common.CatTouchFeedback {
                    AppNavigation(
                        app, splash = savedInstanceState == null && demoScreen == null,
                        demoScreen = demoScreen, demoScan = demoScan,
                    )
                    CrashDialog(app)
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(app: AmiriCutApp, splash: Boolean, demoScreen: String? = null, demoScan: List<String> = emptyList()) {
    val stack = remember { mutableStateListOf<Screen>(if (splash) Screen.Splash else Screen.Home) }
    val current = stack.last()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var creating by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack.removeAt(stack.lastIndex); stack.add(s) }

    // CI screenshot harness: jump straight to the requested screen with demo content
    // (nothing is shown until the demo data exists, so Home lists the seeded projects).
    var demoReady by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(demoScreen == null) }
    if (demoScreen != null) LaunchedEffect(demoScreen) {
        val plan = com.amiri.cut.export.UiDemo.prepare(app, demoScreen, demoScan)
        stack.clear()
        stack.add(Screen.Home)
        when {
            plan.screen == "picker" -> stack.add(Screen.Picker())
            plan.screen == "settings" -> stack.add(Screen.Settings)
            plan.screen == "newproject" -> stack.add(Screen.NewProject)
            plan.projectId != null -> stack.add(Screen.Editor(plan.projectId, importUris = plan.uris, demo = plan))
        }
        demoReady = true
    }
    if (!demoReady) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(Color.Black))
        return
    }

    // The editor handles its own back press (it must save first).
    BackHandler(enabled = stack.size > 1 && current !is Screen.Editor && current !is Screen.Picker) { pop() }

    AnimatedContent(
        targetState = current,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        contentKey = { s -> if (s is Screen.Editor) "editor:" + s.projectId else s.toString() },
        label = "nav",
    ) { screen ->
        when (screen) {
            Screen.Splash -> com.amiri.cut.ui.home.CatSplash { replace(Screen.Home) }
            Screen.Home -> HomeScreen(
                app = app,
                onNewProject = { push(Screen.Picker()) },
                onQuickStart = { q -> push(Screen.Picker(q)) },
                onCustomCanvas = { push(Screen.NewProject) },
                onOpen = { id, recover -> push(Screen.Editor(id, recover = recover)) },
                onSettings = { push(Screen.Settings) },
            )
            is Screen.Picker -> com.amiri.cut.ui.picker.MediaPickerScreen(
                request = pickRequestFor(screen.quick),
                onCancel = { pop() },
                onPicked = { uris ->
                    if (!creating) {
                        creating = true
                        scope.launch {
                            val p = com.amiri.cut.ui.newproject.AutoProject.create(app, uris)
                            creating = false
                            replace(Screen.Editor(p.id, importUris = uris, quick = screen.quick))
                        }
                    }
                },
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
                quick = screen.quick,
                demo = screen.demo,
                onExit = { pop() },
            )
        }
    }
}

private fun pickRequestFor(q: com.amiri.cut.ui.editor.QuickStart?): com.amiri.cut.ui.picker.PickRequest {
    val V = com.amiri.cut.ui.picker.GalleryItem.Kind.VIDEO
    val I = com.amiri.cut.ui.picker.GalleryItem.Kind.IMAGE
    return when (q) {
        null -> com.amiri.cut.ui.picker.PickRequest(setOf(V, I), multiple = true, confirm = "Add")
        com.amiri.cut.ui.editor.QuickStart.REMOVE_BG -> com.amiri.cut.ui.picker.PickRequest(setOf(V), multiple = false, confirm = "Remove background", title = "Pick a video with a person")
        com.amiri.cut.ui.editor.QuickStart.SMART_ROTO -> com.amiri.cut.ui.picker.PickRequest(setOf(V, I), multiple = false, confirm = "Start roto", title = "Pick a video or photo to cut out")
        com.amiri.cut.ui.editor.QuickStart.CLEAN_VOICE -> com.amiri.cut.ui.picker.PickRequest(setOf(V), multiple = false, confirm = "Clean voice", title = "Pick a video with speech")
        com.amiri.cut.ui.editor.QuickStart.STABILIZE -> com.amiri.cut.ui.picker.PickRequest(setOf(V), multiple = false, confirm = "Stabilize", title = "Pick a shaky video")
        com.amiri.cut.ui.editor.QuickStart.BEAT_SYNC -> com.amiri.cut.ui.picker.PickRequest(setOf(V, I), multiple = true, confirm = "Next", title = "Pick the clips to cut on the beat")
        com.amiri.cut.ui.editor.QuickStart.ANIMATE -> com.amiri.cut.ui.picker.PickRequest(setOf(V, I), multiple = true, confirm = "Animate", title = "Pick the clips to animate")
        com.amiri.cut.ui.editor.QuickStart.STICKERS -> com.amiri.cut.ui.picker.PickRequest(setOf(V, I), multiple = true, confirm = "Next", title = "Pick a video or photo for your stickers")
    }
}

@Composable
private fun CrashDialog(app: AmiriCutApp) {
    var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CrashLog.pending(app)) }
    val text = report ?: return
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { CrashLog.markSeen(app); report = null },
        title = { androidx.compose.material3.Text("Amiri Cut closed unexpectedly") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text("Sorry! This report stays on your phone. Copy it and send it if you want the problem fixed.", fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.material3.Text(
                        text, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                        modifier = androidx.compose.ui.Modifier.padding(top = androidx.compose.ui.unit.Dp(8f))
                            .heightIn(max = androidx.compose.ui.unit.Dp(320f)).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { androidx.compose.material3.Text("Copy") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { CrashLog.markSeen(app); report = null }) { androidx.compose.material3.Text("Close") }
        },
    )
}
