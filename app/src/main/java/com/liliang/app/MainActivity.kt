package com.liliang.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.liliang.app.design.LiliangTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: LiliangViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash shows the 力 seal; it must be installed before
        // super.onCreate so the post-splash theme is applied in time.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The app is dark-only by design; the default auto() style follows the
        // SYSTEM theme, which on a light-themed device painted dark status
        // icons over the near-black paper (and a white nav scrim on API 26-28).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Activity-scoped so onStop can reach it; the Factory injects a real
        // WorkoutRepository (tests construct the ViewModel directly).
        model = ViewModelProvider(this, LiliangViewModel.Factory)[LiliangViewModel::class.java]
        setContent {
            LiliangTheme {
                LiliangApp(model)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Backgrounding — or Android reclaiming the process while backgrounded —
        // must never cost logged sets. Quietly persist the live session as a
        // draft; finishing later just completes the same row.
        model.autosaveSessionDraft()
    }
}
