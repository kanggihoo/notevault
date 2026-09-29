package com.ssafy.notevault.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ssafy.notevault.AppContainer
import com.ssafy.notevault.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** 화면 주소. 문자열 경로 대신 @Serializable 객체라 오타가 컴파일 오류가 된다. */
@Serializable
object HomeDestination

@Serializable
object SettingsDestination

@Composable
fun NoteVaultNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeScreen(container, onOpenSettings = { nav.navigate(SettingsDestination) })
        }
        composable<SettingsDestination> {
            SettingsScreen(container, onBack = { nav.popBackStack() })
        }
    }
}
