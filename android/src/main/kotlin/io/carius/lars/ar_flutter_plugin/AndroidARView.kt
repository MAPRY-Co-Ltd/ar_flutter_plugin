package io.carius.lars.ar_flutter_plugin

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARScene

// =============================================================================
// Phase 1 implementation.
//
// SceneView 4.16.x is Compose-only: the imperative ARSceneView/SceneView View
// base classes do not exist in the AAR. We therefore host a ComposeView inside
// the Flutter PlatformView and render the @Composable ARScene inside it.
//
// Flutter does NOT supply ViewTree*Owner objects to PlatformView-hosted views,
// so we wire up our own ArViewLifecycleOwner before the ComposeView is attached
// to a window; otherwise composition crashes with
// "ViewTreeLifecycleOwner not found from <view>".
//
// MethodChannel contract (Dart side, fixed):
//   arsession_$id / arobjects_$id / aranchors_$id
// Node creation and pose/snapshot semantics are stubbed with TODO(Phase2/3).
// =============================================================================
internal class AndroidARView(
        val activity: Activity,
        context: Context,
        messenger: BinaryMessenger,
        id: Int,
        creationParams: Map<String?, Any?>?
) : PlatformView {

    private val sessionManagerChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val objectManagerChannel: MethodChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorManagerChannel: MethodChannel = MethodChannel(messenger, "aranchors_$id")

    // ---- Compose-observable state driving the ARScene ----
    private val showPlanesState = mutableStateOf(true)
    private val planeFindingModeState = mutableStateOf(Config.PlaneFindingMode.HORIZONTAL)
    private val handleTapsState = mutableStateOf(true)

    // ---- Latest AR frame/session, updated from the Compose render thread ----
    @Volatile private var latestSession: Session? = null
    @Volatile private var latestFrame: Frame? = null

    // ---- Self-supplied ViewTree owners (Flutter does not provide them) ----
    private val lifecycleOwner = ArViewLifecycleOwner()

    // Drives the owner from the actual window attach/detach events. Held as a field so
    // it can be removed in dispose() before the owner is destroyed; otherwise a late
    // detach would call onPause() on an already-DESTROYED lifecycle.
    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            lifecycleOwner.onResume()
        }

        override fun onViewDetachedFromWindow(v: View) {
            lifecycleOwner.onPause()
        }
    }

    private val composeView: ComposeView

    init {
        // (1)-(5) Bring our owner to CREATED with restored saved state BEFORE attach.
        lifecycleOwner.onCreate()

        composeView = ComposeView(context).apply {
            // (6) Wire the ViewTree owners. From Kotlin these are the extension
            // functions View.setViewTreeXxxOwner(owner). The JVM-facing
            // ViewTreeLifecycleOwner.set(view, owner) static is a @file:JvmName
            // facade for Java only and is NOT referenceable as a type from Kotlin.
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            addOnAttachStateChangeListener(attachStateListener)

            setContent {
                ARScene(
                        modifier = Modifier.fillMaxSize(),
                        // planeRenderer toggles the built-in plane visualization.
                        planeRenderer = showPlanesState.value,
                        // onSessionFailed MUST be passed as a named argument: the ARScene
                        // signature contains two Function1<Exception,Unit> params and a
                        // positional argument would bind to the wrong one.
                        sessionConfiguration = { _, config ->
                            config.planeFindingMode = planeFindingModeState.value
                        },
                        onSessionUpdated = { session, frame ->
                            latestSession = session
                            latestFrame = frame
                        },
                        onSessionFailed = { e ->
                            sessionManagerChannel.invokeMethod(
                                    "onError",
                                    listOf(e.message ?: "AR session failed")
                            )
                        }
                ) {
                    // ARSceneScope content.
                    // Phase 1: no nodes (preview only).
                    // TODO(Phase2/3): emit AnchorNode/PlaneNode/HitResultNode and the
                    //  cube(4)/rectangleFrame(5) geometry driven by Compose state derived
                    //  from object/anchor MethodChannel calls.
                }
            }
        }

        installSessionHandler()
        installObjectHandler()
        installAnchorHandler()
    }

    // -------------------------------------------------------------------------
    // session channel: arsession_$id
    // -------------------------------------------------------------------------
    private fun installSessionHandler() {
        sessionManagerChannel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            when (call.method) {
                "init" -> {
                    // Compose state may be updated from the main thread (MethodChannel thread).
                    val showPlanes = call.argument<Boolean>("showPlanes")
                    if (showPlanes != null) showPlanesState.value = showPlanes

                    val handleTaps = call.argument<Boolean>("handleTaps")
                    if (handleTaps != null) handleTapsState.value = handleTaps

                    val planeDetectionConfig = call.argument<Int>("planeDetectionConfig")
                    if (planeDetectionConfig != null) {
                        planeFindingModeState.value = when (planeDetectionConfig) {
                            0 -> Config.PlaneFindingMode.DISABLED
                            1 -> Config.PlaneFindingMode.HORIZONTAL
                            2 -> Config.PlaneFindingMode.VERTICAL
                            3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                            else -> Config.PlaneFindingMode.HORIZONTAL
                        }
                    }
                    result.success(null)
                }

                "getCameraPose" -> {
                    // ARCore Pose.toMatrix outputs a column-major (OpenGL) float[16].
                    // The Dart contract documents "row-major"; whether a transpose is
                    // required against Dart vector_math Matrix4 (column-major storage)
                    // is UNVERIFIED.
                    // TODO(Phase2): verify row/column-major agreement with vector_math
                    //  on a real device; transpose here if needed.
                    val frame = latestFrame
                    val camera = frame?.camera
                    if (camera != null && camera.trackingState == TrackingState.TRACKING) {
                        val matrix = FloatArray(16)
                        camera.displayOrientedPose.toMatrix(matrix, 0)
                        result.success(matrix.map { it.toDouble() })
                    } else {
                        result.success(null)
                    }
                }

                "getAnchorPose" -> {
                    // TODO(Phase2): resolve anchor by id and return its world transform.
                    result.success(null)
                }

                "snapshot" -> {
                    // TODO(Phase2): capture the ARScene surface to a PNG byte array.
                    result.success(null)
                }

                "dispose" -> {
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    // -------------------------------------------------------------------------
    // object channel: arobjects_$id
    // -------------------------------------------------------------------------
    private fun installObjectHandler() {
        objectManagerChannel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            when (call.method) {
                "init" -> result.success(null)

                "addNode" -> {
                    Log.d(TAG, "object.addNode: ${call.arguments}")
                    // TODO(Phase3): create the node geometry and emit it in ARSceneScope.
                    result.success(true)
                }

                "addNodeToPlaneAnchor" -> {
                    Log.d(TAG, "object.addNodeToPlaneAnchor: ${call.arguments}")
                    // TODO(Phase3): create the node and attach it to the plane anchor.
                    result.success(true)
                }

                "transformationChanged" -> {
                    // TODO(Phase3): apply the new transform to the named node.
                    result.success(null)
                }

                "removeNode" -> {
                    // TODO(Phase3): remove the named node.
                    result.success("")
                }

                else -> result.notImplemented()
            }
        }
    }

    // -------------------------------------------------------------------------
    // anchor channel: aranchors_$id
    // -------------------------------------------------------------------------
    private fun installAnchorHandler() {
        anchorManagerChannel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            when (call.method) {
                "addAnchor" -> {
                    Log.d(TAG, "anchor.addAnchor: ${call.arguments}")
                    // TODO(Phase3): create an ARCore anchor and emit an AnchorNode.
                    result.success(true)
                }

                "removeAnchor" -> {
                    // TODO(Phase3): detach the named anchor.
                    result.success("")
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun getView(): View = composeView

    override fun dispose() {
        sessionManagerChannel.setMethodCallHandler(null)
        objectManagerChannel.setMethodCallHandler(null)
        anchorManagerChannel.setMethodCallHandler(null)
        // Remove the attach listener before destroying the owner so a late detach
        // cannot drive the lifecycle after DESTROYED.
        composeView.removeOnAttachStateChangeListener(attachStateListener)
        lifecycleOwner.onDestroy()
    }

    private companion object {
        private const val TAG = "AndroidARView"
    }
}
