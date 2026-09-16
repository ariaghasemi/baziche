package com.baziche.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.baziche.app.di.AppContainer
import com.baziche.app.ui.screens.DashboardScreen
import com.baziche.app.ui.screens.DashboardViewModel
import com.baziche.app.ui.screens.LoginScreen
import com.baziche.app.ui.screens.ProjectDetailScreen
import com.baziche.app.ui.screens.ProjectDetailViewModel
import com.baziche.app.ui.screens.ProjectsScreen
import com.baziche.app.ui.screens.ProjectsViewModel
import com.baziche.app.ui.screens.RegisterScreen
import com.baziche.app.ui.screens.SettingsScreen
import com.baziche.app.ui.screens.SettingsViewModel
import com.baziche.app.ui.screens.SharedAuthViewModel
import com.baziche.app.ui.screens.SplashScreen
import com.baziche.app.ui.screens.SplashViewModel
import com.baziche.app.ui.util.vmFactory
import com.baziche.editor.EditorViewModel
import com.baziche.editor.ui.SceneEditorScreen
import com.baziche.preview.PreviewViewModel
import com.baziche.preview.ui.PreviewScreen

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val DASHBOARD = "dashboard"
    const val PROJECTS = "projects"
    const val DETAIL = "project/{id}"
    const val EDITOR = "editor/{id}"
    const val PREVIEW = "preview/{id}"
    const val SETTINGS = "settings"
    fun detail(id: String) = "project/$id"
    fun editor(id: String) = "editor/$id"
    fun preview(id: String) = "preview/$id"
}

@Composable
fun NavGraph(c: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            val vm: SplashViewModel = viewModel(factory = vmFactory { SplashViewModel(c.sessionStore) })
            SplashScreen(vm) { dest ->
                nav.navigate(dest) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
        }
        composable(Routes.LOGIN) {
            val vm: SharedAuthViewModel = viewModel(factory = vmFactory { SharedAuthViewModel(c.authRepository) })
            LoginScreen(vm,
                onLoggedIn = { nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                onGoRegister = { nav.navigate(Routes.REGISTER) })
        }
        composable(Routes.REGISTER) {
            val vm: SharedAuthViewModel = viewModel(factory = vmFactory { SharedAuthViewModel(c.authRepository) })
            RegisterScreen(vm,
                onRegistered = { nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.REGISTER) { inclusive = true } } },
                onGoLogin = { nav.popBackStack() })
        }
        composable(Routes.DASHBOARD) {
            val vm: DashboardViewModel = viewModel(factory = vmFactory { DashboardViewModel(c.authRepository) })
            DashboardScreen(vm,
                onProjects = { nav.navigate(Routes.PROJECTS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLoggedOut = { nav.navigate(Routes.LOGIN) { popUpTo(Routes.DASHBOARD) { inclusive = true } } })
        }
        composable(Routes.PROJECTS) {
            val vm: ProjectsViewModel = viewModel(factory = vmFactory { ProjectsViewModel(c.projectRepository) })
            ProjectsScreen(vm, onOpen = { id -> nav.navigate(Routes.detail(id)) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
            val id = backStack.arguments?.getString("id").orEmpty()
            val vm: ProjectDetailViewModel = viewModel(factory = vmFactory { ProjectDetailViewModel(c.projectRepository, id) })
            ProjectDetailScreen(vm,
                onBack = { nav.popBackStack() },
                onOpenEditor = { nav.navigate(Routes.editor(id)) },
                onPreview = { nav.navigate(Routes.preview(id)) })
        }
        composable(Routes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
            val id = backStack.arguments?.getString("id").orEmpty()
            val vm: EditorViewModel = viewModel(factory = vmFactory { EditorViewModel(c.projectRepository, id) })
            SceneEditorScreen(vm,
                onBack = { nav.popBackStack() },
                onPreview = { nav.navigate(Routes.preview(id)) })
        }
        composable(Routes.PREVIEW, arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
            val id = backStack.arguments?.getString("id").orEmpty()
            val appContext = LocalContext.current.applicationContext
            val vm: PreviewViewModel = viewModel(factory = vmFactory { PreviewViewModel(c.projectRepository, c.api, appContext, id) })
            PreviewScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(c.authRepository, c.languageManager, c.apiBaseUrl) })
            SettingsScreen(vm,
                onLoggedOut = { nav.navigate(Routes.LOGIN) { popUpTo(Routes.SETTINGS) { inclusive = true } } },
                onBack = { nav.popBackStack() })
        }
    }
}
