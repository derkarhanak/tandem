package io.github.samsyk.tandem.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.samsyk.tandem.Prefs
import io.github.samsyk.tandem.engine.Engine
import io.github.samsyk.tandem.engine.SyncService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Prefs.onboarded && Prefs.enabled) SyncService.turnOn(this)
        setContent { TandemTheme { Root(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.visible = true
    }

    override fun onStop() {
        vm.visible = false
        super.onStop()
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val engine by Engine.state.collectAsStateWithLifecycle()
    val waiting by Engine.waiting.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            vm.message = null
            snackbar.showSnackbar(it)
        }
    }
    LaunchedEffect(engine) { vm.refresh() }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (!vm.onboarded) {
                OnboardingScreen(vm)
            } else {
                val screen = vm.stack.last()
                BackHandler(enabled = vm.stack.size > 1) { vm.back() }
                AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(vm, engine, waiting)
                        Screen.Pair -> PairScreen(vm)
                        Screen.AddFolder -> AddFolderScreen(vm)
                        is Screen.FolderDetail -> FolderScreen(vm, s.id)
                        is Screen.DeviceDetail -> DeviceScreen(vm, s.id)
                        Screen.Settings -> SettingsScreen(vm, engine)
                        Screen.Log -> LogScreen(vm)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
        }
    }
}
