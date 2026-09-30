package com.ssafy.notevault.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ssafy.notevault.AppContainer
import com.ssafy.notevault.settings.SettingsScreen
import com.ssafy.notevault.sync.SubscriptionsScreen
import com.ssafy.notevault.vault.NoteScreen
import kotlinx.serialization.Serializable

/** 화면 주소. 문자열 경로 대신 @Serializable 객체라 오타가 컴파일 오류가 된다. */
@Serializable
object HomeDestination

@Serializable
object SettingsDestination

@Serializable
object SubscriptionsDestination

/** 인자가 있는 화면은 data class. 볼트 기준 상대 경로를 넘긴다. */
@Serializable
data class NoteDestination(val path: String)

@Composable
fun NoteVaultNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeScreen(
                container,
                onOpenSettings = { nav.navigate(SettingsDestination) },
                onOpenSubscriptions = { nav.navigate(SubscriptionsDestination) },
                onOpenFile = { nav.navigate(NoteDestination(it)) },
            )
        }
        composable<SettingsDestination> {
            SettingsScreen(container, onBack = { nav.popBackStack() })
        }
        composable<SubscriptionsDestination> {
            SubscriptionsScreen(container, onBack = { nav.popBackStack() })
        }
        composable<NoteDestination> { entry ->
            NoteScreen(container, path = entry.toRoute<NoteDestination>().path, onBack = { nav.popBackStack() })
        }
    }
}
