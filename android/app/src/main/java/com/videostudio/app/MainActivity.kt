package com.videostudio.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.videostudio.app.ui.HomeScreen
import com.videostudio.app.ui.NewVideoScreen
import com.videostudio.app.ui.ProjectScreen
import com.videostudio.app.ui.SettingsScreen
import com.videostudio.app.ui.StudioTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as App
        handleReturn(intent)
        setContent { StudioTheme { Nav(app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReturn(intent)
    }

    /** videostudio://connected (accounts linked) and videostudio://signed-in (Google) bring us back here. */
    private fun handleReturn(intent: Intent?) {
        val host = intent?.data?.takeIf { it.scheme == "videostudio" }?.host ?: return
        returns.value = host to System.currentTimeMillis()
    }

    companion object {
        /** Last deep link back into the app; screens refresh what they show when it changes. */
        val returns = MutableStateFlow<Pair<String, Long>?>(null)
    }
}

@Composable
private fun Nav(app: App) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                app,
                onNew = { nav.navigate("new") },
                onOpen = { id -> nav.navigate("project/$id") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("new") {
            NewVideoScreen(
                app,
                onBack = { nav.popBackStack() },
                onCreated = { id -> nav.navigate("project/$id") { popUpTo("home") } },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("project/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            ProjectScreen(
                app,
                id = entry.arguments?.getString("id") ?: "",
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(app, onBack = { nav.popBackStack() })
        }
    }
}
