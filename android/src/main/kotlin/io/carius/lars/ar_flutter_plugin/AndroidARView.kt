package io.carius.lars.ar_flutter_plugin

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARScene
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.node.Node
import java.io.ByteArrayOutputStream

// =============================================================================
// Phase 2 implementation (SceneView 4.16.x, Compose-only).
//
// SceneView 4.16.x has no imperative ARSceneView/SceneView View base class, so
// we host a ComposeView inside the Flutter PlatformView and render @Composable
// ARScene. Flutter does not supply ViewTree*Owner objects, so we wire our own
// ArViewLifecycleOwner before attach (otherwise composition crashes).
//
// The Dart MethodChannel contract is imperative (addAnchor / removeAnchor /
// onPlaneOrPointTap ...), but ARScene is declarative: nodes are emitted from a
// content lambda that recomposes when state changes. We therefore keep an
// observable [anchorEntries] list and re-emit AnchorNode() for each entry; the
// channel handlers mutate that list.
//
// Channels (Dart side, fixed): arsession_$id / arobjects_$id / aranchors_$id.
// Node geometry (cube(4) / rectangleFrame(5)) is still Phase 3.
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

    // Anchors are emitted declaratively in the ARScene content lambda. Mutating
    // this snapshot list from the channel handlers triggers recomposition, which
    // adds/removes the corresponding AnchorNode.
    private data class AnchorEntry(val name: String, val anchor: Anchor)
    private val anchorEntries = mutableStateListOf<AnchorEntry>()

    // ---- Latest AR frame/session, updated from the Compose render thread ----
    @Volatile private var latestSession: Session? = null
    @Volatile private var latestFrame: Frame? = null

    private val mainHandler = Handler(Looper.getMainLooper())

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

    // Tap handling. The Node argument is the SceneView node under the tap (if any);
    // a tap on a node fires onNodeTap, otherwise we run an ARCore hit-test for
    // plane/point taps. Gesture callbacks arrive on the main thread.
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) {
            handleTap(e, node)
        }
    }

    private val composeView: ComposeView

    init {
        // Bring our owner to CREATED with restored saved state BEFORE attach.
        lifecycleOwner.onCreate()

        composeView = ComposeView(context).apply {
            // Wire the ViewTree owners via the Kotlin extension functions. The
            // JVM-facing ViewTreeLifecycleOwner.set(view, owner) static is a
            // @file:JvmName facade for Java only and is NOT referenceable from Kotlin.
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            addOnAttachStateChangeListener(attachStateListener)

            setContent {
                ARScene(
                        modifier = Modifier.fillMaxSize(),
                        planeRenderer = showPlanesState.value,
                        sessionConfiguration = { _, config ->
                            config.planeFindingMode = planeFindingModeState.value
                        },
                        onSessionUpdated = { session, frame ->
                            latestSession = session
                            latestFrame = frame
                        },
                        // onSessionFailed MUST be a named argument: ARScene has two
                        // Function1<Exception,Unit> params and a positional arg binds wrong.
                        onSessionFailed = { e ->
                            sessionManagerChannel.invokeMethod(
                                    "onError",
                                    listOf(e.message ?: "AR session failed")
                            )
                        },
                        onGestureListener = gestureListener
                ) {
                    // ARSceneScope content: re-emit an AnchorNode per tracked anchor.
                    // key() keeps node identity stable across recompositions.
                    anchorEntries.forEach { entry ->
                        key(entry.name) {
                            // Positional first arg + trailing content lambda; the
                            // intermediate AnchorNode params all have defaults.
                            AnchorNode(entry.anchor) {
                                // TODO(Phase3): emit child geometry (cube/rectangleFrame)
                                //  attached to this anchor, driven by object-channel state.
                            }
                        }
                    }
                }
            }
        }

        installSessionHandler()
        installObjectHandler()
        installAnchorHandler()
    }

    // -------------------------------------------------------------------------
    // Tap → onNodeTap / onPlaneOrPointTap
    // -------------------------------------------------------------------------
    private fun handleTap(motionEvent: MotionEvent, node: Node?) {
        if (node != null) {
            objectManagerChannel.invokeMethod("onNodeTap", listOf(node.name))
            return
        }
        if (!handleTapsState.value) return

        val frame = latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        val hits = try {
            frame.hitTest(motionEvent)
        } catch (e: Exception) {
            Log.w(TAG, "hitTest failed", e)
            return
        }

        // serializeHitResult tags plane/point/undefined; keep only plane & point.
        val planeAndPoint = hits.filter { serializeHitResult(it)["type"] != 0 }
        if (planeAndPoint.isEmpty()) return

        val serialized = ArrayList(planeAndPoint.map { serializeHitResult(it) })
        sessionManagerChannel.invokeMethod("onPlaneOrPointTap", serialized)
    }

    // -------------------------------------------------------------------------
    // session channel: arsession_$id
    // -------------------------------------------------------------------------
    private fun installSessionHandler() {
        sessionManagerChannel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            when (call.method) {
                "init" -> {
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
                    // ARCore Pose.toMatrix is column-major (OpenGL). Dart vector_math
                    // Matrix4.fromList reads column-major storage too, so NO transpose
                    // is needed (verified against MatrixConverter). [Phase1 TODO resolved]
                    val frame = latestFrame
                    val camera = frame?.camera
                    if (camera != null && camera.trackingState == TrackingState.TRACKING) {
                        result.success(serializePose(camera.displayOrientedPose))
                    } else {
                        result.success(null)
                    }
                }

                "getAnchorPose" -> {
                    val anchorId = call.argument<String>("anchorId")
                    val entry = anchorEntries.firstOrNull { it.name == anchorId }
                    if (entry != null) {
                        result.success(serializePose(entry.anchor.pose))
                    } else {
                        result.error("Error", "could not get anchor pose", null)
                    }
                }

                "snapshot" -> takeSnapshot(result)

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
                    when (call.argument<Int>("type")) {
                        0 -> { // plane anchor
                            val transform = call.argument<ArrayList<Double>>("transformation")
                            val name = call.argument<String>("name")
                            if (name != null && transform != null) {
                                result.success(addPlaneAnchor(transform, name))
                            } else {
                                result.success(false)
                            }
                        }
                        else -> result.success(false)
                    }
                }

                "removeAnchor" -> {
                    call.argument<String>("name")?.let { removeAnchor(it) }
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    // Creates an ARCore anchor at the given world transform and registers it for
    // declarative emission. Idempotent on name.
    private fun addPlaneAnchor(transform: List<Double>, name: String): Boolean {
        val session = latestSession ?: return false
        return try {
            val anchor = session.createAnchor(deserializePose(transform))
            anchorEntries.removeAll { it.name == name }
            anchorEntries.add(AnchorEntry(name, anchor))
            true
        } catch (e: Exception) {
            Log.e(TAG, "addPlaneAnchor failed", e)
            false
        }
    }

    private fun removeAnchor(name: String) {
        val index = anchorEntries.indexOfFirst { it.name == name }
        if (index < 0) return
        val entry = anchorEntries.removeAt(index)
        try {
            entry.anchor.detach()
        } catch (e: Exception) {
            Log.w(TAG, "anchor.detach failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // snapshot: PixelCopy the live AR rendering surface to a PNG byte array.
    // SceneView renders into a SurfaceView (SurfaceType.Surface) or TextureView
    // (SurfaceType.TextureSurface); we handle both by walking the view tree.
    // -------------------------------------------------------------------------
    private fun takeSnapshot(result: MethodChannel.Result) {
        val surfaceView = findChild(composeView) { it is SurfaceView && it.width > 0 && it.height > 0 } as SurfaceView?
        if (surfaceView != null) {
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            val thread = HandlerThread("PixelCopier").apply { start() }
            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    mainHandler.post { result.success(compressPng(bitmap)) }
                } else {
                    mainHandler.post { result.error("snapshot", "PixelCopy failed: $copyResult", null) }
                }
                thread.quitSafely()
            }, Handler(thread.looper))
            return
        }

        val textureView = findChild(composeView) { it is TextureView && it.isAvailable } as TextureView?
        val bitmap = textureView?.bitmap
        if (bitmap != null) {
            result.success(compressPng(bitmap))
        } else {
            result.error("snapshot", "AR rendering surface not ready", null)
        }
    }

    private fun compressPng(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return stream.toByteArray()
    }

    // Depth-first search of the view tree for the first child matching [predicate].
    private fun findChild(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChild(view.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    override fun getView(): View = composeView

    override fun dispose() {
        sessionManagerChannel.setMethodCallHandler(null)
        objectManagerChannel.setMethodCallHandler(null)
        anchorManagerChannel.setMethodCallHandler(null)
        // Detach anchors so ARCore releases them.
        anchorEntries.forEach {
            try {
                it.anchor.detach()
            } catch (e: Exception) {
                Log.w(TAG, "anchor.detach failed during dispose", e)
            }
        }
        anchorEntries.clear()
        // Remove the attach listener before destroying the owner so a late detach
        // cannot drive the lifecycle after DESTROYED.
        composeView.removeOnAttachStateChangeListener(attachStateListener)
        lifecycleOwner.onDestroy()
    }

    private companion object {
        private const val TAG = "AndroidARView"
    }
}
