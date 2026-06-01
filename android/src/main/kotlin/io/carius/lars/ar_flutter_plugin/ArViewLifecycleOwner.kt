package io.carius.lars.ar_flutter_plugin

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Self-contained owner that provides [LifecycleOwner], [ViewModelStoreOwner] and
 * [SavedStateRegistryOwner] for a [androidx.compose.ui.platform.ComposeView] that is
 * hosted inside a Flutter [io.flutter.plugin.platform.PlatformView].
 *
 * Flutter's PlatformView does not supply ViewTree*Owner objects to the views it hosts.
 * Without them, AbstractComposeView throws "ViewTreeLifecycleOwner not found from <view>"
 * (IllegalStateException) the moment the view is attached to a window and composition starts.
 *
 * Initialization order is critical (see [onCreate]):
 *   1. construct this owner
 *   2. SavedStateRegistryController.create(this)
 *   3. controller.performAttach()        (required since savedstate 1.3.x)
 *   4. controller.performRestore(null)    BEFORE moving Lifecycle to CREATED
 *   5. lifecycleRegistry.currentState = CREATED
 * Calling performRestore after CREATED throws on the savedstate side.
 */
internal class ArViewLifecycleOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val isDestroyed: Boolean
        get() = lifecycleRegistry.currentState == Lifecycle.State.DESTROYED

    /** Restore saved state and bring the lifecycle to CREATED. Call once, on the main thread. */
    fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Bring the lifecycle to RESUMED (view attached / foreground). No-op once destroyed. */
    fun onResume() {
        if (isDestroyed) return
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Bring the lifecycle down to STARTED (view detached / background). No-op once destroyed. */
    fun onPause() {
        if (isDestroyed) return
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    /** Destroy the lifecycle and clear the ViewModelStore to avoid leaks. Idempotent. */
    fun onDestroy() {
        if (isDestroyed) return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
