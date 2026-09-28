package com.yet.pets.host

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidHostAvailabilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun permissionAvailabilityRefreshesOnActualActivityResume() {
        AndroidOverlayPermission.overrideForTest = false
        try {
            var observed: PetSystemOverlayAvailability? = null
            rule.setContent { observed = rememberPetSystemOverlayAvailability() }
            rule.waitForIdle()
            assertEquals(PetSystemOverlayAvailability.PermissionRequired, observed)

            AndroidOverlayPermission.overrideForTest = true
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitUntil(5_000) { observed == PetSystemOverlayAvailability.Available }

            AndroidOverlayPermission.overrideForTest = false
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitUntil(5_000) { observed == PetSystemOverlayAvailability.PermissionRequired }
        } finally {
            AndroidOverlayPermission.overrideForTest = null
        }
    }

    @Test
    fun realOverlayRespondsToRevocationAndLaterGrantOnResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(Settings.canDrawOverlays(context), "grant SYSTEM_ALERT_WINDOW app-op before this test")
        val parsed = PetPackageParser.parseTrustedMetadata(
            "{}", "permission-test", SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
        )
        val definition = assertIs<PetParseOutcome.Success>(parsed).definition
        val state = PetHostState()
        val mode = mutableStateOf(PetHostMode.SystemOverlay)
        var view: androidx.compose.ui.platform.ComposeView? = null
        AndroidOverlayTestProbe.onViewCreated = { created, _, _ -> view = created }
        AndroidOverlayPermission.overrideForTest = false
        try {
            rule.setContent { PetHost(definition, ByteArray(16), state, mode.value) }
            rule.waitUntil(5_000) { state.platformState == PetHostPlatformState.PermissionRequired }
            assertTrue(view?.isAttachedToWindow != true)

            AndroidOverlayPermission.overrideForTest = true
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitUntil(5_000) { state.platformState == PetHostPlatformState.Showing && view?.isAttachedToWindow == true }
            val firstView = view

            AndroidOverlayPermission.overrideForTest = false
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitUntil(5_000) { state.platformState == PetHostPlatformState.PermissionRequired && firstView?.isAttachedToWindow == false }

            AndroidOverlayPermission.overrideForTest = true
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitUntil(5_000) { state.platformState == PetHostPlatformState.Showing && firstView?.isAttachedToWindow == true }
            assertTrue(firstView === view)
            rule.runOnIdle { mode.value = PetHostMode.InApp }
            rule.waitUntil(5_000) { firstView?.isAttachedToWindow == false }
        } finally {
            AndroidOverlayPermission.overrideForTest = null
            AndroidOverlayTestProbe.onViewCreated = null
        }
    }
}
