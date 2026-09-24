package com.sivrad.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
                        onStop = controller::stop,
                        onListen = controller::listen,
                        onRetry = controller::retry,
                        onConfirm = controller::answerConfirmation,
                        onDismiss = ::hide,
                    )
                }
            }
        }
        installOwners(view)
        // The window recomposer is resolved from the root view, so the
        // owners have to be on the window's decor view as well.
        window?.window?.decorView?.let(::installOwners)
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
        controller.listen()
    }

    override fun onHide() {
        // An unlock prompt (for send_sms and friends) can hide the overlay
        // while the tool waits on it; tearing down then would lose the call.
        // TODO(on-device): check whether requestDismissKeyguard from
        // startAssistantActivity hides this session at all, and whether the
        // overlay comes back afterwards.
        if (!controller.busyUnlocking) controller.reset()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onDestroy() {
        controller.reset()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }
}
