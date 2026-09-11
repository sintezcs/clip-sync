package com.clipsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.clipsync.ui.components.SettingsGroup
import com.clipsync.ui.components.SettingsRow
import com.clipsync.ui.theme.ClipSyncTheme

@Preview(name = "Cover unpaired", widthDp = 340, heightDp = 780)
@Preview(name = "Short window", widthDp = 600, heightDp = 340)
@Preview(name = "Large type", widthDp = 340, heightDp = 780, fontScale = 2f)
@Composable
private fun UnpairedPreview() = ClipSyncTheme(false) {
    SettingsPage("ClipSync") {
        StatusGroup(null, "Setup needed", false, false) {}
        SettingsGroup { SettingsRow("Connection", "Pair your Mac"); SettingsRow("Clipboard"); SettingsRow("Privacy") }
    }
}

@Preview(name = "Dark ready", widthDp = 400, heightDp = 800)
@Composable
private fun DarkReadyPreview() = ClipSyncTheme(true) {
    SettingsPage("ClipSync") { StatusGroup("Studio Mac with a deliberately long synthetic name", "Ready", true, true) {} }
}

@Preview(name = "Paused", widthDp = 400, heightDp = 800)
@Composable
private fun PausedPreview() = ClipSyncTheme(false) {
    SettingsPage("ClipSync") { StatusGroup("Studio Mac", "Paused", true, false) {} }
}

@Preview(name = "Reconnecting", widthDp = 400, heightDp = 800)
@Composable
private fun ReconnectingPreview() = ClipSyncTheme(false) {
    SettingsPage("ClipSync") { StatusGroup("Studio Mac", "Reconnecting", true, true) {} }
}

@Preview(name = "Expired pairing", widthDp = 340, heightDp = 780)
@Composable
private fun PairingPreview() = ClipSyncTheme(false) {
    SettingsPage("Review pairing") {
        PairingReview("studio-mac.local", "7010", "A".repeat(43), "", false, false, false,
            {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Helper unavailable / enlarged text", widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun HelperUnavailablePreview() = ClipSyncTheme(false) {
    SettingsPage("Setup & diagnostics") {
        SettingsGroup {
            SettingsRow("Shizuku", "Start the helper again after restarting your phone.")
            SettingsRow("Проверка доступности буфера обмена", "Длинная тестовая подпись для проверки переноса строк.")
        }
    }
}
