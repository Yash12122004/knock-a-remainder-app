package app.knock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.knock.ui.MainViewModel
import app.knock.ui.screens.*
import app.knock.ui.theme.LocalKnock
import app.knock.ui.theme.KnockTheme

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_ROUTE = "route" }

    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = true)
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        addOnNewIntentListener { i -> pendingRoute.value = i.getStringExtra(EXTRA_ROUTE) }
        setContent {
            val app = application as KnockApp
            val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(app))
            val settings by vm.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.darkTheme) { applySystemBars(settings.darkTheme) }
            KnockTheme(dark = settings.darkTheme) {
                KnockNav(vm, settings.onboardingDone, pendingRoute)
            }
        }
    }
}

/**
 * Status and navigation bar icons follow Knock's own theme, not the phone's. The default
 * (auto) style reads the phone's setting, so a light-mode phone drew dark icons over
 * Knock's dark screens, and a dark-mode phone drew light icons over its light ones.
 */
private fun ComponentActivity.applySystemBars(dark: Boolean) {
    val clear = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val PERMISSIONS = "permissions"
    const val CAPTURE = "capture"
    const val CONFIRM = "confirm"
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val TASK = "task/{id}"
    fun task(id: Long) = "task/$id"
}

@Composable
fun KnockNav(vm: MainViewModel, onboardingDone: Boolean, pendingRoute: MutableState<String?>) {
    val nav: NavHostController = rememberNavController()
    val c = LocalKnock.current
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = current in setOf(Routes.HOME, Routes.CALENDAR, Routes.PROGRESS, Routes.SETTINGS)

    LaunchedEffect(pendingRoute.value) {
        val r = pendingRoute.value ?: return@LaunchedEffect
        pendingRoute.value = null
        if (onboardingDone) nav.navigate(r) { launchSingleTop = true }
    }

    Scaffold(
        containerColor = c.bg,
        bottomBar = {
            if (showBar) NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                val items = listOf(
                    Triple(Routes.HOME, "Tasks", Icons.Filled.Home),
                    Triple(Routes.CALENDAR, "Calendar", Icons.Filled.CalendarMonth),
                    Triple(Routes.PROGRESS, "Progress", Icons.Filled.BarChart),
                    Triple(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
                )
                items.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick = {
                            nav.navigate(route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.accentOn, selectedTextColor = c.text, indicatorColor = c.accent,
                            unselectedIconColor = c.secondary, unselectedTextColor = c.secondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = if (onboardingDone) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.ONBOARDING) { OnboardingScreen(onContinue = { nav.navigate(Routes.PERMISSIONS) }) }
            composable(Routes.PERMISSIONS) {
                PermissionsScreen(vm, onContinue = {
                    vm.updateSettings { it.copy(onboardingDone = true) }
                    nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                })
            }
            composable(Routes.CAPTURE) {
                CaptureScreen(vm, onParsed = { nav.navigate(Routes.CONFIRM) { popUpTo(Routes.CAPTURE) { inclusive = true } } }, onBack = { nav.popBackStack() })
            }
            composable(Routes.CONFIRM) {
                ConfirmScreen(vm, onReRecord = { nav.navigate(Routes.CAPTURE) { popUpTo(Routes.CONFIRM) { inclusive = true } } },
                    onAdded = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                    onBack = { nav.popBackStack() })
            }
            composable(Routes.HOME) {
                HomeScreen(vm, onCapture = { nav.navigate(Routes.CAPTURE) }, onConfirm = { nav.navigate(Routes.CONFIRM) },
                    onOpenTask = { nav.navigate(Routes.task(it)) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(Routes.CALENDAR) { CalendarScreen(vm, onOpenTask = { nav.navigate(Routes.task(it)) }) }
            composable(Routes.PROGRESS) { ProgressScreen(vm) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, onPermissions = { nav.navigate(Routes.PERMISSIONS) }) }
            composable(Routes.TASK, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1L
                TaskDetailScreen(vm, id, onBack = { nav.popBackStack() })
            }
        }
    }
}
