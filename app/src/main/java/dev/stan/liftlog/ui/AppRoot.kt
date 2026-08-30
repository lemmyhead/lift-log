package dev.stan.liftlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stan.liftlog.timer.SessionManager

sealed interface Screen {
    data object Home : Screen
    data object Active : Screen
    data object Stats : Screen
    data class Detail(val trainingId: Long) : Screen
}

@Composable
fun AppRoot() {
    var screen: Screen by remember { mutableStateOf(Screen.Home) }
    val session by SessionManager.state.collectAsStateWithLifecycle()

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    // A finished session should never leave the user staring at a dead active screen.
    // Keyed on `active` so it fires on the transition, not during the moment between
    // tapping "Start training" and the session row being written.
    LaunchedEffect(session.active) {
        if (!session.active && screen is Screen.Active) screen = Screen.Home
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            onStartOrResume = { screen = Screen.Active },
            onOpenStats = { screen = Screen.Stats },
            onOpenTraining = { screen = Screen.Detail(it) }
        )
        Screen.Active -> ActiveScreen(onDone = { screen = Screen.Home })
        Screen.Stats -> StatsScreen(onBack = { screen = Screen.Home })
        is Screen.Detail -> DetailScreen(trainingId = s.trainingId, onBack = { screen = Screen.Home })
    }
}
