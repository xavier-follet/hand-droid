package com.follet.jotter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onStop() {
        super.onStop()
        store.exporter.flushNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(applicationContext)
        setContent {
            val dark = when (store.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            SideEffect { // status/navigation bar icons must follow the in-app theme, not only the system one
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                }
            }
            JotterTheme(dark) {
                Surface {
                    var openNote by remember { mutableStateOf<Note?>(null) }
                    var settings by remember { mutableStateOf(false) }
                    val n = openNote
                    when {
                        n != null && n.kind == "ink" -> InkEditor(store, n) { openNote = null }
                        n != null -> TextEditor(store, n) { openNote = null }
                        settings -> { BackHandler { settings = false }; SettingsScreen(store) { settings = false } }
                        else -> Home(store, { openNote = it }, { settings = true })
                    }
                }
            }
        }
    }
}
