package app.tishe.audio

import android.graphics.Bitmap
import android.app.NotificationManager
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EditorScreenTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        screen.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.takeScreenshot().let { image ->
            File(instrument.targetContext.cacheDir, name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun opusImportEffectsExportAndBackgroundPlayback() {
        val context = screen.activity
        val model = (context.application as TisheApplication).editor
        val input = File(context.cacheDir, "preview.opus").apply {
            InstrumentationRegistry.getInstrumentation().context.assets.open("preview.opus").use { stream ->
                outputStream().use { stream.copyTo(it) }
            }
        }
        val output = File(context.cacheDir, "ui-export.wav")
        try {
            screen.onNodeWithText("Open audio file").assertIsDisplayed()
            screenshot("editor-empty.png")
            screen.runOnUiThread { model.importTrack(Uri.fromFile(input)) }
            screen.waitUntil(15000) { !model.state.value.busy && model.state.value.name != null }
            screen.onNodeWithText("Play").performScrollTo().performClick()
            screen.waitUntil(5000) { model.state.value.playing && model.state.value.position > .1 }
            screen.onNodeWithContentDescription("Enable repeat").performScrollTo().performClick()
            screen.runOnIdle { assertTrue(model.state.value.repeat) }
            screen.onNodeWithContentDescription("Disable repeat").performClick()
            screen.runOnIdle { assertFalse(model.state.value.repeat) }
            screen.onNodeWithContentDescription("Enable repeat").performClick()
            screenshot("editor-player.png")
            screen.onNodeWithContentDescription("Speed").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.7f) }
            screen.onNodeWithContentDescription("Reverb").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.45f) }
            screen.runOnIdle {
                assertEquals(.7f, model.state.value.speed)
                assertEquals(.45f, model.state.value.wet)
                assertTrue(model.state.value.playing)
            }
            screenshot("editor-effects.png")
            for ((effect, amount) in listOf("Bass boost" to .6f, "Echo" to .3f, "Low-pass" to .5f, "Lo-fi" to .2f, "Flanger" to .4f)) {
                screen.onNodeWithContentDescription(effect).performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(amount) }
            }
            val extra = ExtraEffects(.6f, .3f, .5f, .2f, .4f)
            screen.runOnIdle { assertEquals(extra, model.state.value.extra) }
            screenshot("editor-extra-effects.png")
            screen.onNodeWithContentDescription("Hear original").performScrollTo().performClick()
            screen.runOnIdle { assertTrue(model.state.value.original) }
            screen.onNodeWithContentDescription("Hear effects").performClick()
            screen.runOnIdle { assertEquals(extra, model.state.value.extra) }
            screen.runOnUiThread { model.export(Uri.fromFile(output)) }
            screen.waitUntil(30000) { !model.state.value.busy }
            screen.runOnIdle {
                assertEquals("WAV saved", model.state.value.message)
                assertTrue(output.length() > 44)
            }
            screen.runOnUiThread { model.seek(0.0) }
            screen.onNodeWithText("Play").performScrollTo().performClick()
            screen.waitUntil(5000) { model.state.value.playing }
            screen.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val position = model.state.value.position
            screen.waitUntil(5000) { model.state.value.position > position + .5 }
            assertTrue(model.state.value.playing)
            screen.activityRule.scenario.close()
            val afterClose = model.state.value.position
            screen.waitUntil(5000) { model.state.value.playing && model.state.value.position > afterClose + .5 }
            assertTrue(model.state.value.playing)
            ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                reopened.onActivity { assertSame(model, (it.application as TisheApplication).editor) }
                reopened.onActivity { assertTrue(model.state.value.repeat) }
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            val notification = manager.activeNotifications.single { it.id == 1 }.notification
            assertNotNull(notification.extras.getParcelable<android.os.Parcelable>("android.mediaSession"))
            notification.actions.first().actionIntent.send()
            screen.waitUntil(5000) { !model.state.value.playing && manager.activeNotifications.none { it.id == 1 } }
        } finally { input.delete(); output.delete() }
    }
}
