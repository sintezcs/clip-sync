package com.clipsync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.clipsync.ui.components.SettingsSwitch
import com.clipsync.ui.layout.AdaptiveSettingsLayout
import com.clipsync.ui.theme.ClipSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchHasOneAccessibleToggleAction() {
        var changes = 0
        compose.setContent {
            ClipSyncTheme {
                var enabled by remember { mutableStateOf(false) }
                SettingsSwitch("Sync", "Connect devices", enabled) { enabled = it; changes++ }
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
        compose.onNodeWithText("Sync").performClick()
        assertEquals(1, changes)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsOn()
    }

    @Test fun sixDigitCodeDoesNotPairWithoutReviewedFingerprintAndExplicitClick() {
        var confirmations = 0
        compose.setContent {
            ClipSyncTheme {
                var verified by remember { mutableStateOf(false) }
                SettingsPage("Review pairing") {
                    PairingReview("test-mac.local", "7010", "A".repeat(43), "123456", verified,
                        replacing = true, busy = false, onHost = {}, onPort = {}, onFingerprint = {},
                        onCode = {}, onVerified = { verified = it }, onConfirm = { confirmations++ })
                }
            }
        }
        compose.onNodeWithText("Replace paired Mac").performScrollTo().assertIsNotEnabled()
        assertEquals(0, confirmations)
        compose.onNodeWithText("I compared the fingerprint").performScrollTo().performClick()
        assertEquals(0, confirmations)
        compose.onNodeWithText("Replace paired Mac").performScrollTo().performClick()
        assertEquals(1, confirmations)
    }

    @Test fun singleUseLinkStillRequiresFingerprintReviewAndExplicitConfirmation() {
        var confirmations = 0
        compose.setContent {
            ClipSyncTheme {
                var verified by remember { mutableStateOf(false) }
                SettingsPage("Review pairing") {
                    PairingReview("test-mac.local", "7010", "A".repeat(43), "", verified,
                        replacing = false, busy = false, onHost = {}, onPort = {}, onFingerprint = {},
                        onCode = {}, onVerified = { verified = it }, onConfirm = { confirmations++ }, hasQrSecret = true)
                }
            }
        }
        compose.onNodeWithText("Six-digit pairing code").assertDoesNotExist()
        compose.onNodeWithText("Confirm pairing").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I compared the fingerprint").performScrollTo().performClick()
        assertEquals(0, confirmations)
        compose.onNodeWithText("Confirm pairing").performScrollTo().performClick()
        assertEquals(1, confirmations)
    }

    @Test fun savedSelectionSurvivesRecreationAndWidthChanges() {
        val restoration = StateRestorationTester(compose)
        var width by mutableStateOf(340.dp)
        restoration.setContent {
            var selected by rememberSaveable { mutableStateOf(true) }
            ClipSyncTheme {
                Box(Modifier.requiredWidth(width).height(700.dp)) {
                    AdaptiveSettingsLayout(selected, list = { Text("Settings list") }, detail = {
                        androidx.compose.material3.TextButton(onClick = { selected = false }) { Text("Back to settings") }
                    })
                }
            }
        }
        compose.onNodeWithText("Back to settings").assertExists()
        compose.runOnIdle { width = 900.dp }
        compose.onNodeWithText("Settings list").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { width = 340.dp }
        compose.onNodeWithText("Settings list").assertDoesNotExist()
        compose.onNodeWithText("Back to settings").assertExists().performClick()
        compose.onNodeWithText("Settings list").assertExists()
    }
}
