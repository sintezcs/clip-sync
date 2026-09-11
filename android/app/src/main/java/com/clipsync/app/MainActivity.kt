package com.clipsync.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.clipsync.share.MacShareActivity
import com.clipsync.service.ClipForegroundService
import com.clipsync.storage.Prefs
import com.clipsync.ui.SettingsScreen
import com.clipsync.ui.theme.ClipSyncTheme

class MainActivity : ComponentActivity() {
    private val deepLinkUri = mutableStateOf<Uri?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptLink(intent)
        ensureSyncRunning()
    }

    override fun onResume() {
        super.onResume()
        ensureSyncRunning()
    }

    private fun ensureSyncRunning() {
        val prefs = Prefs(applicationContext)
        if (prefs.hasPairing() && prefs.syncEnabled) ClipForegroundService.ensureRunning(applicationContext)
    }

    private fun acceptLink(source: Intent) {
        source.data?.takeIf { it.scheme == "clipsync" && it.host == "pair" }?.let {
            deepLinkUri.value = it
        }
        // Neither logs nor Activity saved state should retain the one-time code.
        source.data = null
        intent?.data = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptLink(intent)
        registerMacShareShortcut()
        val themePrefs = getSharedPreferences("clipsync_ui", MODE_PRIVATE)
        setContent {
            var mode by remember { mutableStateOf(themePrefs.getString("theme_mode", "system") ?: "system") }
            val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val layoutFlow = remember { WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this) }
            val layout by layoutFlow.collectAsState(initial = null)
            val fold = layout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ClipSyncTheme(dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(deepLinkUri = deepLinkUri.value, onDeepLinkConsumed = { deepLinkUri.value = null },
                        themeMode = mode, onThemeMode = {
                            mode = it
                            themePrefs.edit().putString("theme_mode", it).apply()
                        }, fold = fold)
                }
            }
        }
    }

    private fun registerMacShareShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "mac_share_target")
            .setShortLabel("Mac")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_mac_share))
            .setIntent(
                Intent(Intent.ACTION_SEND, null, this, MacShareActivity::class.java)
            )
            .setCategories(setOf("com.clipsync.category.SHARE_TARGET"))
            .setLongLived(true)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
    }
}
