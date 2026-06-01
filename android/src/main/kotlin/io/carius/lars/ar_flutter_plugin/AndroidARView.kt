package io.carius.lars.ar_flutter_plugin

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.SceneScope
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.ARScene
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.node.Node
import java.io.ByteArrayOutputStream

// =============================================================================
// Phase 2 + 3 implementation (SceneView 4.16.x, Compose-only).
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
// Phase 3 adds the node state adapter: addNode/addNodeToPlaneAnchor register a
// NodeState that the content lambda re-emits as a CubeNode (cube=4) or a 4-edge
// rectangle frame (rectangleFrame=5, MAPRY custom). glTF/GLB (0..3) stay in the
// contract but are not rendered yet.
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
    private val handlePansState = mutableStateOf(false)
    private val handleRotationState = mutableStateOf(false)

    // Anchors are emitted declaratively in the ARScene content lambda. Mutating
    // this snapshot list from the channel handlers triggers recomposition, which
    // adds/removes the corresponding AnchorNode. childNodeNames holds the names of
    // nodes attached to this anchor (addNodeToPlaneAnchor).
    private data class AnchorEntry(
            val name: String,
            val anchor: Anchor,
            val childNodeNames: SnapshotStateList<String> = mutableStateListOf()
    )
    private val anchorEntries = mutableStateListOf<AnchorEntry>()

    // ---- Node state adapter layer (imperative contract -> declarative Compose) ----
    // addNode/addNodeToPlaneAnchor register a NodeState; the ARScene content lambda
    // re-emits a CubeNode (or 4 for a rectangle frame) per entry on recomposition.
    private class NodeState(
            val name: String,
            val type: Int,
            val uri: String,
            val data: Map<*, *>?,
            initialTransform: Mat4
    ) {
        // The live SceneView node, captured on first nodeApply. Lets channel calls
        // (transformationChanged) push transforms straight onto the node without
        // fighting gesture-driven changes.
        @Volatile var liveNode: Node? = null
        // The node's current transform: the initial placement, then updated by Dart
        // pushes and by gesture-end. Applied to the node once on creation.
        @Volatile var currentTransform: Mat4 = initialTransform
    }

    // All nodes by name, plus which are scene-root (addNode without an anchor).
    private val nodeStatesByName = mutableStateMapOf<String, NodeState>()
    private val rootNodeNames = mutableStateListOf<String>()

    // ---- Latest AR frame/session, updated from the Compose render thread ----
    @Volatile private var latestSession: Session? = null
    @Volatile private var latestFrame: Frame? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Self-supplied ViewTree owners (Flutter does not provide them) ----
    private val lifecycleOwner = ArViewLifecycleOwner()

    // ---- Self-driven Compose recomposer ----
    // FlutterActivity does NOT extend ComponentActivity, so Flutter sets no
    // ViewTree*Owner on the FlutterView. Compose installs its WINDOW recomposer on
    // the window root (the FlutterView) and reads its lifecycle there, so it crashes
    // with "ViewTreeLifecycleOwner not found from FlutterView" regardless of the
    // owners we set on our own ComposeView. Providing our own Recomposer as the
    // parent CompositionContext bypasses the window recomposer entirely, making the
    // setup independent of the host Activity type.
    private val recomposerContext = AndroidUiDispatcher.CurrentThread
    private val recomposerScope = CoroutineScope(recomposerContext)
    private val recomposer = Recomposer(recomposerContext)

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

            // Use our own recomposer as the parent so Compose never reaches for the
            // FlutterView-based window recomposer. Must be set before the composition
            // is created (i.e. before attach). Drive it on the Compose UI dispatcher,
            // which supplies the frame clock recomposition needs.
            setParentCompositionContext(recomposer)

            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            addOnAttachStateChangeListener(attachStateListener)

            setContent {
                ARScene(
                        modifier = Modifier.fillMaxSize(),
                        // Render into a TextureView, not the default SurfaceView. A
                        // SurfaceView owns a separate surface that z-orders above the
                        // Flutter UI and hides the survey controls; a TextureView
                        // composites inside the view tree / Flutter texture layer, so
                        // Flutter widgets overlay the AR view correctly. (snapshot's
                        // PixelCopy already handles the TextureView path.)
                        surfaceType = SurfaceType.TextureSurface,
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
                    // ARSceneScope content: re-emit an AnchorNode per tracked anchor,
                    // with each anchor's child nodes nested under it (so their transform
                    // is interpreted relative to the anchor, matching the old plugin).
                    // key() keeps node identity stable across recompositions.
                    anchorEntries.forEach { entry ->
                        key(entry.name) {
                            // Positional first arg + trailing content lambda; the
                            // intermediate AnchorNode params all have defaults.
                            AnchorNode(entry.anchor) {
                                entry.childNodeNames.forEach { childName ->
                                    nodeStatesByName[childName]?.let { state ->
                                        key(childName) { RenderNode(state) }
                                    }
                                }
                            }
                        }
                    }
                    // Nodes added without an anchor live directly under the scene.
                    rootNodeNames.forEach { name ->
                        nodeStatesByName[name]?.let { state ->
                            key(name) { RenderNode(state) }
                        }
                    }
                }
            }
        }

        // Start the recomposition loop. runRecomposeAndApplyChanges suspends until the
        // recomposer is cancelled (in dispose()).
        recomposerScope.launch {
            recomposer.runRecomposeAndApplyChanges()
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

                    // pan/rotation editability of placed nodes (SVF adjustment mode).
                    val handlePans = call.argument<Boolean>("handlePans")
                    if (handlePans != null) handlePansState.value = handlePans

                    val handleRotation = call.argument<Boolean>("handleRotation")
                    if (handleRotation != null) handleRotationState.value = handleRotation

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
                    // node.toMap() is passed at the top level (no wrapper).
                    val state = nodeStateFromMap(call.arguments as? Map<*, *>)
                    if (state != null) {
                        nodeStatesByName[state.name] = state
                        if (!rootNodeNames.contains(state.name)) rootNodeNames.add(state.name)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }

                "addNodeToPlaneAnchor" -> {
                    // { "node": <toMap>, "anchor": <toJson> }
                    val nodeMap = call.argument<Map<*, *>>("node")
                    val anchorMap = call.argument<Map<*, *>>("anchor")
                    val state = nodeStateFromMap(nodeMap)
                    val anchorName = anchorMap?.get("name") as? String
                    if (state != null) {
                        nodeStatesByName[state.name] = state
                        val entry = anchorEntries.firstOrNull { it.name == anchorName }
                        if (entry != null) {
                            if (!entry.childNodeNames.contains(state.name)) {
                                entry.childNodeNames.add(state.name)
                            }
                        } else {
                            // Anchor not (yet) known: fall back to a scene-root node.
                            if (!rootNodeNames.contains(state.name)) rootNodeNames.add(state.name)
                        }
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }

                "transformationChanged" -> {
                    val name = call.argument<String>("name")
                    val transform = call.argument<ArrayList<Double>>("transformation")
                    val state = if (name != null) nodeStatesByName[name] else null
                    if (state != null && transform != null && transform.size == 16) {
                        val mat = deserializeMat4(transform)
                        state.currentTransform = mat
                        // Apply immediately if the node already exists; otherwise it is
                        // picked up from currentTransform when the node is created.
                        state.liveNode?.transform = mat
                    }
                    result.success(null)
                }

                "removeNode" -> {
                    call.argument<String>("name")?.let { removeNode(it) }
                    result.success("")
                }

                else -> result.notImplemented()
            }
        }
    }

    // Removes a node from every collection; recomposition disposes its SceneView node.
    private fun removeNode(name: String) {
        nodeStatesByName.remove(name)
        rootNodeNames.remove(name)
        anchorEntries.forEach { it.childNodeNames.remove(name) }
    }

    // Builds a NodeState from a serialized ARNode map (type/name/uri/transformation/data).
    private fun nodeStateFromMap(map: Map<*, *>?): NodeState? {
        if (map == null) return null
        val name = map["name"] as? String ?: return null
        val type = (map["type"] as? Number)?.toInt() ?: return null
        val uri = map["uri"] as? String ?: ""
        val data = map["data"] as? Map<*, *>
        val transformList = (map["transformation"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
        val transform = if (transformList != null && transformList.size == 16) {
            deserializeMat4(transformList)
        } else {
            Mat4()
        }
        return NodeState(name, type, uri, data, transform)
    }

    // -------------------------------------------------------------------------
    // Declarative node geometry. These are @Composable extensions on SceneScope
    // (so the SceneScope member CubeNode/materialLoader resolve) and also see the
    // outer AndroidARView members. They are invoked from the ARScene content lambda
    // both at the scene root and inside an AnchorNode's NodeScope.
    // -------------------------------------------------------------------------
    @Composable
    private fun SceneScope.RenderNode(state: NodeState) {
        when (state.type) {
            4 -> RenderCube(state)
            5 -> RenderRectangleFrame(state)
            // 0..3 glTF/GLB: kept in the contract (addNode returns true) but not
            // rendered yet. SVF does not use model nodes; declarative ModelNode
            // loading (suspend ModelLoader) is deferred to a later phase.
            else -> Log.d(TAG, "RenderNode: type ${state.type} not rendered yet")
        }
    }

    @Composable
    private fun SceneScope.RenderCube(state: NodeState) {
        val width = dim(state.data, "cubeWidth", 0.1f)
        val height = dim(state.data, "cubeHeight", 0.1f)
        val length = dim(state.data, "cubeLength", 0.1f)
        val color = colorOf(state.data, "cubeColor")
        // remember keyed on color so the MaterialInstance is created once. The
        // DisposableEffect (declared before CubeNode so it disposes last) frees it.
        val material = remember(color) { materialLoader.createColorInstance(color, 0f, 1f, 0f) }
        DisposableEffect(material) {
            onDispose { runCatching { materialLoader.destroyMaterialInstance(material) } }
        }
        // Read editability state here so a change recomposes and re-runs nodeApply.
        val positionEditable = handlePansState.value
        val rotationEditable = handleRotationState.value
        CubeNode(
                size = Float3(width, height, length),
                center = Float3(0f),
                materialInstance = material,
                apply = { applyNode(this, state, positionEditable, rotationEditable) }
        ) {}
    }

    // rectangleFrame(5) = MAPRY custom. Four thin cubes form the frame edges. The
    // top edge is the parent node (owns transform + gestures); the other three are
    // children so they inherit the parent's placement. Edge dimensions mirror the
    // old ArModelBuilder: edge length = frameSize + frameWidth (corner overlap),
    // frameHeight = Y thickness, edges offset by ±halfSize on z (top/bottom) and
    // x (left/right).
    @Composable
    private fun SceneScope.RenderRectangleFrame(state: NodeState) {
        val frameSize = dim(state.data, "frameSize", 1.0f)
        val frameWidth = dim(state.data, "frameWidth", 0.02f)
        val frameHeight = dim(state.data, "frameHeight", 0.001f)
        val color = colorOf(state.data, "frameColor")
        val material = remember(color) { materialLoader.createColorInstance(color, 0f, 1f, 0f) }
        DisposableEffect(material) {
            onDispose { runCatching { materialLoader.destroyMaterialInstance(material) } }
        }
        val positionEditable = handlePansState.value
        val rotationEditable = handleRotationState.value
        val half = frameSize / 2f
        val longLen = frameSize + frameWidth
        CubeNode(
                size = Float3(longLen, frameHeight, frameWidth),
                center = Float3(0f, 0f, half), // top edge (parent: owns transform + gestures)
                materialInstance = material,
                apply = { applyNode(this, state, positionEditable, rotationEditable) }
        ) {
            CubeNode(
                    size = Float3(longLen, frameHeight, frameWidth),
                    center = Float3(0f, 0f, -half), // bottom edge
                    materialInstance = material,
                    apply = { name = state.name }
            ) {}
            CubeNode(
                    size = Float3(frameWidth, frameHeight, longLen),
                    center = Float3(-half, 0f, 0f), // left edge
                    materialInstance = material,
                    apply = { name = state.name }
            ) {}
            CubeNode(
                    size = Float3(frameWidth, frameHeight, longLen),
                    center = Float3(half, 0f, 0f), // right edge
                    materialInstance = material,
                    apply = { name = state.name }
            ) {}
        }
    }

    // nodeApply runs on (re)composition. Capture the live node and apply name +
    // initial transform once; wire gestures once; keep editability reactive (the
    // caller reads handlePans/handleRotation state so a change recomposes here).
    private fun applyNode(node: Node, state: NodeState, positionEditable: Boolean, rotationEditable: Boolean) {
        if (state.liveNode == null) {
            state.liveNode = node
            node.name = state.name
            node.transform = state.currentTransform
            wireGestures(node, state)
        }
        node.isPositionEditable = positionEditable
        node.isRotationEditable = rotationEditable
        node.isScaleEditable = false
    }

    // Node gesture callbacks are `var` properties (not setOnXxx methods). pan/rotation
    // only fire when the matching editability flag is on. End callbacks sync the
    // node's new transform back to state and notify Dart.
    private fun wireGestures(node: Node, state: NodeState) {
        node.onMoveBegin = { _, _ ->
            objectManagerChannel.invokeMethod("onPanStart", state.name); true
        }
        node.onMove = { _, _, _ ->
            objectManagerChannel.invokeMethod("onPanChange", state.name); true
        }
        node.onMoveEnd = { _, _ ->
            state.currentTransform = node.transform
            objectManagerChannel.invokeMethod(
                    "onPanEnd",
                    mapOf("name" to state.name, "transform" to serializeMat4(node.transform))
            )
        }
        node.onRotateBegin = { _, _ ->
            objectManagerChannel.invokeMethod("onRotationStart", state.name); true
        }
        node.onRotate = { _, _, _ ->
            objectManagerChannel.invokeMethod("onRotationChange", state.name); true
        }
        node.onRotateEnd = { _, _ ->
            state.currentTransform = node.transform
            objectManagerChannel.invokeMethod(
                    "onRotationEnd",
                    mapOf("name" to state.name, "transform" to serializeMat4(node.transform))
            )
        }
    }

    private fun dim(data: Map<*, *>?, key: String, default: Float): Float =
            (data?.get(key) as? Number)?.toFloat() ?: default

    // Dart sends ARGB ints (0xAARRGGBB); large values arrive as Long over the
    // channel, so toInt() truncates to the low 32 bits (the ARGB value).
    private fun colorOf(data: Map<*, *>?, key: String): Int =
            (data?.get(key) as? Number)?.toInt() ?: Color.RED

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
        // Drop node state so recomposition disposes the SceneView nodes.
        nodeStatesByName.clear()
        rootNodeNames.clear()
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
        // Stop the recomposition loop and release its coroutine scope.
        recomposer.cancel()
        recomposerScope.cancel()
    }

    private companion object {
        private const val TAG = "AndroidARView"
    }
}
