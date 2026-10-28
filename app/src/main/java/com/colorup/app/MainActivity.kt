package com.colorup.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.colorup.app.ui.ColorUpScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var model: ColoringViewModel
    private lateinit var sounds: GameSounds
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            val request = model.pendingExport
            if (request == null) {
                model.showMessage("请再点一次保存作品。")
            } else {
                Thread({
                    val result = runCatching { ArtworkExporter.write(contentResolver, uri, request) }
                    runOnUiThread {
                        model.showMessage(if (result.isSuccess) "作品已保存为 PNG 图片。" else "暂时无法导出作品，请再试一次。")
                    }
                }, "color-up-export").start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[ColoringViewModel::class.java]
        sounds = GameSounds(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFFF5C5C),
                secondary = Color(0xFFFFD93D),
                background = Color(0xFFFFF8EC),
            )) {
                val state = model.uiState
                LaunchedEffect(state.screen) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (state.screen == GameScreen.PAINT) controller.hide(WindowInsetsCompat.Type.systemBars())
                    else controller.show(WindowInsetsCompat.Type.systemBars())
                }
                LaunchedEffect(state.errorMessage) {
                    state.errorMessage?.let { message -> delay(4500); model.dismissMessage(message) }
                }
                LaunchedEffect(state.showCelebration) {
                    if (state.showCelebration) playSound("complete")
                }
                BackHandler(enabled = state.screen != GameScreen.HOME || state.showCelebration) {
                    if (state.showCelebration) model.closeCelebration()
                    else model.navigate(if (state.screen == GameScreen.PAINT) GameScreen.GALLERY else GameScreen.HOME)
                }
                Box(Modifier.fillMaxSize()) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        ColorUpScreen(
                            state = state,
                            onPageSelected = model::selectPage,
                            onCrayonSelected = { crayon -> model.selectCrayon(crayon); playSound("select") },
                            onToolSelected = { tool -> model.selectTool(tool); playSound("select") },
                            onWidthSelected = model::selectWidth,
                            onUndo = model::undo,
                            onClear = { model.clear(); playSound("erase") },
                            onStrokeStart = { point ->
                                model.startStroke(point)
                                model.uiState.activeStroke?.let { stroke ->
                                    playSound(if (stroke.tool == com.colorup.core.Tool.ERASER) "erase" else "crayon")
                                }
                            },
                            onStrokeMove = model::moveStroke,
                            onStrokeEnd = model::endStroke,
                            onStrokeCancel = model::settleStroke,
                            onNavigate = model::navigate,
                            onExport = {
                                model.settleStroke()
                                val latest = model.uiState
                                model.pendingExport = ExportRequest(latest.page, latest.strokes)
                                exportLauncher.launch("涂个彩-${latest.page.title}.png")
                            },
                            onSoundToggle = model::toggleSound,
                            onCloseCelebration = model::closeCelebration,
                            onNextPicture = model::nextPicture,
                            onPalettePageChange = { playSound("select") },
                        )
                    }
                    state.errorMessage?.let { message ->
                        Surface(
                            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp),
                            color = Color(0xFFFFE3D6),
                            shape = MaterialTheme.shapes.medium,
                        ) { Text(message, Modifier.padding(16.dp), color = Color(0xFF443C53)) }
                    }
                }
            }
        }
    }

    private fun playSound(name: String) {
        if (model.uiState.soundEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) sounds.play(name)
    }

    override fun onStop() {
        if (::model.isInitialized) model.settleStroke()
        super.onStop()
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }
}
