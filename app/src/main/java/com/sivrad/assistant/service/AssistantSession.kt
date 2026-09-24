package com.sivrad.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sivrad.assistant.sivrad
import com.sivrad.assistant.ui.AssistantOverlay
import com.sivrad.assistant.ui.SivradTheme
import com.sivrad.core.tools.KeyguardGate
import com.sivrad.core.tools.ToolEnvironment
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The overlay. The system shows it on the assist gesture, over the lock
 * screen too (TYPE_VOICE_INTERACTION windows sit above the keyguard), and it
 * starts listening immediately.
 *
 * Compose needs a lifecycle, saved-state and view-model owner on the view
 * tree, and a VoiceInteractionSession is none of those, so the session
 * provides them itself (the same approach as Home Assistant's assist module).
 */
class AssistantSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private val app = context.sivrad

    private val environment = object : ToolEnvironment {
        override val context: Context get() = this@AssistantSession.context
        override val httpAllowlist: List<String> get() = app.settings.values.value.httpAllowlist

        override fun startActivity(intent: Intent, dismissAssistant: Boolean) {
            // The session's window is visible, which is what lets this
            // process start activities.
            // TODO(on-device): confirm no background-activity-launch block on
            // GrapheneOS when started from the lock screen (timers/alarms).
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (dismissAssistant) main.post { hide() }
        }
    }

    private val gate = KeyguardGate(context) { intent -> startAssistantActivity(intent) }
    private val controller = AssistantController(app, environment, gate, scope)

    /** Drives the overlay's enter/exit animation; the window hides once the exit has played. */
    private val visible = MutableStateFlow(false)
    private var closing = false

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SivradTheme {
                    AssistantOverlay(
                        state = controller.state,
                        visible = visible,
                        onStop = controller::stop,
                        onListen = controller::listen,
                        onRetry = controller::retry,
                        onConfirm = controller::answerConfirmation,
                        onSubmit = controller::submit,
                        onTypeInstead = controller::typeInstead,
                        onDismiss = ::dismiss,
                        onHidden = {
                            if (closing) {
                                closing = false
                                hide()
                            }
                        },
                    )
                }
            }
        }
        installOwners(view)
        // The window recomposer is resolved from the root view, so the
        // owners have to be on the window's decor view as well.
        window?.window?.let { w ->
            w.decorView.let(::installOwners)
            // The overlay animates itself in and out.
            w.setWindowAnimations(0)
            // Session windows are made unable to take keyboard input by
            // default; typing instead of speaking needs it back. Insets go to
            // Compose so the card can ride up with the keyboard.
            // TODO(on-device): check the keyboard comes up over the lock screen.
            w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(w, false)
        }
        return view
    }

    private fun installOwners(v: View) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        closing = false
        visible.value = true
        controller.listen()
    }

    override fun onHide() {
        // An unlock prompt (for send_sms and friends) can hide the overlay
        // while the tool waits on it; tearing down then would lose the call.
        // TODO(on-device): check whether requestDismissKeyguard from
        // startAssistantActivity hides this session at all, and whether the
        // overlay comes back afterwards.
        if (!controller.busyUnlocking) controller.reset()
        closing = false
        visible.value = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onBackPressed() = dismiss()

    /** Plays the exit animation, then hides. */
    private fun dismiss() {
        closing = true
        visible.value = false
    }

    override fun onDestroy() {
        controller.reset()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }
}
