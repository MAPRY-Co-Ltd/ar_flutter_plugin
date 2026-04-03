package io.carius.lars.ar_flutter_plugin

import android.R
import android.app.Activity
import android.content.Context
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.assets.RenderableSource

import java.util.concurrent.CompletableFuture
import android.net.Uri
import android.view.Gravity
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.math.MathHelper
import com.google.ar.sceneform.rendering.*
import com.google.ar.sceneform.utilities.Preconditions
import com.google.ar.sceneform.ux.*

import io.carius.lars.ar_flutter_plugin.Serialization.*

import io.flutter.FlutterInjector
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.security.AccessController


// Responsible for creating Renderables and Nodes
class ArModelBuilder {

    // Creates feature point node
    fun makeFeaturePointNode(context: Context, xPos: Float, yPos: Float, zPos: Float): Node {
        val featurePoint = Node()                 
        var cubeRenderable: ModelRenderable? = null      
        MaterialFactory.makeOpaqueWithColor(context, Color(android.graphics.Color.YELLOW))
        .thenAccept { material ->
            val vector3 = Vector3(0.01f, 0.01f, 0.01f)
            cubeRenderable = ShapeFactory.makeCube(vector3, Vector3(xPos, yPos, zPos), material)
            cubeRenderable?.isShadowCaster = false
            cubeRenderable?.isShadowReceiver = false
        }
        featurePoint.renderable = cubeRenderable

        return featurePoint
    }

    // Creates a coordinate system model at the world origin (X-axis: red, Y-axis: green, Z-axis:blue)
    // The code for this function is adapted from Alexander's stackoverflow answer (https://stackoverflow.com/questions/48908358/arcore-how-to-display-world-origin-or-axes-in-debug-mode) 
    fun makeWorldOriginNode(context: Context): Node {
        val axisSize = 0.1f
        val axisRadius = 0.005f

        val rootNode = Node()
        val xNode = Node()
        val yNode = Node()
        val zNode = Node()

        rootNode.addChild(xNode)
        rootNode.addChild(yNode)
        rootNode.addChild(zNode)

        xNode.worldPosition = Vector3(axisSize / 2, 0f, 0f)
        xNode.worldRotation = Quaternion.axisAngle(Vector3(0f, 0f, 1f), 90f)

        yNode.worldPosition = Vector3(0f, axisSize / 2, 0f)

        zNode.worldPosition = Vector3(0f, 0f, axisSize / 2)
        zNode.worldRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), 90f)

        MaterialFactory.makeOpaqueWithColor(context, Color(255f, 0f, 0f))
                .thenAccept { redMat ->
                    xNode.renderable = ShapeFactory.makeCylinder(axisRadius, axisSize, Vector3.zero(), redMat)
                }

        MaterialFactory.makeOpaqueWithColor(context, Color(0f, 255f, 0f))
                .thenAccept { greenMat ->
                    yNode.renderable = ShapeFactory.makeCylinder(axisRadius, axisSize, Vector3.zero(), greenMat)
                }

        MaterialFactory.makeOpaqueWithColor(context, Color(0f, 0f, 255f))
                .thenAccept { blueMat ->
                    zNode.renderable = ShapeFactory.makeCylinder(axisRadius, axisSize, Vector3.zero(), blueMat)
                }

        return rootNode
    }

    // Creates a cube node with given size and color using ShapeFactory (no gltf needed)
    fun makeCubeNode(context: Context, transformationSystem: TransformationSystem, objectManagerChannel: MethodChannel, enablePans: Boolean, enableRotation: Boolean, name: String, width: Float, height: Float, length: Float, colorValue: Long, transformation: ArrayList<Double>): CompletableFuture<CustomTransformableNode> {
        val completableFutureNode: CompletableFuture<CustomTransformableNode> = CompletableFuture()

        val cubeNode = CustomTransformableNode(transformationSystem, objectManagerChannel, enablePans, enableRotation)

        val a = (colorValue shr 24 and 0xFF).toFloat() / 255f
        val r = (colorValue shr 16 and 0xFF).toFloat() / 255f
        val g = (colorValue shr 8 and 0xFF).toFloat() / 255f
        val b = (colorValue and 0xFF).toFloat() / 255f

        MaterialFactory.makeOpaqueWithColor(context, Color(r, g, b))
            .thenAccept { material ->
                val size = Vector3(width, height, length)
                val renderable = ShapeFactory.makeCube(size, Vector3.zero(), material)
                renderable.isShadowCaster = false
                renderable.isShadowReceiver = false
                cubeNode.renderable = renderable
                cubeNode.name = name
                val transform = deserializeMatrix4(transformation)
                cubeNode.worldScale = transform.first
                cubeNode.worldPosition = transform.second
                cubeNode.worldRotation = transform.third
                completableFutureNode.complete(cubeNode)
            }
            .exceptionally { throwable ->
                completableFutureNode.completeExceptionally(throwable)
                null
            }

        return completableFutureNode
    }

    // Creates a rectangle frame node with 4 cube edges as children (parent-child structure)
    fun makeRectangleFrameNode(context: Context, transformationSystem: TransformationSystem, objectManagerChannel: MethodChannel, enablePans: Boolean, enableRotation: Boolean, name: String, frameSize: Float, frameWidth: Float, frameHeight: Float, colorValue: Long, transformation: ArrayList<Double>): CompletableFuture<CustomTransformableNode> {
        val completableFutureNode: CompletableFuture<CustomTransformableNode> = CompletableFuture()

        val parentNode = CustomTransformableNode(transformationSystem, objectManagerChannel, enablePans, enableRotation)

        val a = (colorValue shr 24 and 0xFF).toFloat() / 255f
        val r = (colorValue shr 16 and 0xFF).toFloat() / 255f
        val g = (colorValue shr 8 and 0xFF).toFloat() / 255f
        val b = (colorValue and 0xFF).toFloat() / 255f

        MaterialFactory.makeOpaqueWithColor(context, Color(r, g, b))
            .thenAccept { material ->
                // Edge definitions: top, bottom, left, right
                data class Edge(val w: Float, val h: Float, val l: Float, val x: Float, val y: Float, val z: Float)
                val halfSize = frameSize / 2f
                val edges = listOf(
                    Edge(frameSize + frameWidth, frameHeight, frameWidth, 0f, 0f, halfSize),   // top
                    Edge(frameSize + frameWidth, frameHeight, frameWidth, 0f, 0f, -halfSize),  // bottom
                    Edge(frameWidth, frameHeight, frameSize + frameWidth, -halfSize, 0f, 0f),  // left
                    Edge(frameWidth, frameHeight, frameSize + frameWidth, halfSize, 0f, 0f),   // right
                )

                for (edge in edges) {
                    val renderable = ShapeFactory.makeCube(
                        Vector3(edge.w, edge.h, edge.l),
                        Vector3.zero(),
                        material
                    )
                    renderable.isShadowCaster = false
                    renderable.isShadowReceiver = false

                    val childNode = Node()
                    childNode.renderable = renderable
                    childNode.localPosition = Vector3(edge.x, edge.y, edge.z)
                    parentNode.addChild(childNode)
                }

                parentNode.name = name
                val transform = deserializeMatrix4(transformation)
                parentNode.localScale = transform.first
                parentNode.localPosition = transform.second
                parentNode.localRotation = transform.third
                completableFutureNode.complete(parentNode)
            }
            .exceptionally { throwable ->
                completableFutureNode.completeExceptionally(throwable)
                null
            }

        return completableFutureNode
    }

    // Creates a node form a given gltf model path or URL. The gltf asset loading in Scenform is asynchronous, so the function returns a completable future of type Node
    fun makeNodeFromGltf(context: Context, transformationSystem: TransformationSystem, objectManagerChannel: MethodChannel, enablePans: Boolean, enableRotation: Boolean, name: String, modelPath: String, transformation: ArrayList<Double>): CompletableFuture<CustomTransformableNode> {
        val completableFutureNode: CompletableFuture<CustomTransformableNode> = CompletableFuture()

        val gltfNode = CustomTransformableNode(transformationSystem, objectManagerChannel, enablePans, enableRotation)

        ModelRenderable.builder()
                .setSource(context, RenderableSource.builder().setSource(
                        context,
                        Uri.parse(modelPath),
                        RenderableSource.SourceType.GLTF2)
                        .build())
                .setRegistryId(modelPath)
                .build()
                .thenAccept{ renderable ->
                    gltfNode.renderable = renderable
                    gltfNode.name = name
                    val transform = deserializeMatrix4(transformation)
                    gltfNode.worldScale = transform.first
                    gltfNode.worldPosition = transform.second
                    gltfNode.worldRotation = transform.third
                    completableFutureNode.complete(gltfNode)
                }
                .exceptionally { throwable ->
                    completableFutureNode.completeExceptionally(throwable)
                    null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
                }

    return completableFutureNode
    }

    // Creates a node form a given glb model path or URL. The gltf asset loading in Sceneform is asynchronous, so the function returns a compleatable future of type Node
    fun makeNodeFromGlb(context: Context, transformationSystem: TransformationSystem, objectManagerChannel: MethodChannel, enablePans: Boolean, enableRotation: Boolean, name: String, modelPath: String, transformation: ArrayList<Double>): CompletableFuture<CustomTransformableNode> {
        val completableFutureNode: CompletableFuture<CustomTransformableNode> = CompletableFuture()

        val gltfNode = CustomTransformableNode(transformationSystem, objectManagerChannel, enablePans, enableRotation)
        //gltfNode.scaleController.isEnabled = false
        //gltfNode.translationController.isEnabled = false

        /*gltfNode.removeTransformationController(translationController)
        gltfNode.addTra
        val customTranslationController = DragController(
            gltfNode,
            transformationSystem.dragRecognizer,
            objectManagerChannel,
            transformationSystem
        )*/

        ModelRenderable.builder()
                .setSource(context, RenderableSource.builder().setSource(
                        context,
                        Uri.parse(modelPath),
                        RenderableSource.SourceType.GLB)
                        .build())
                .setRegistryId(modelPath)
                .build()
                .thenAccept{ renderable ->
                    gltfNode.renderable = renderable
                    gltfNode.name = name
                    val transform = deserializeMatrix4(transformation)
                    gltfNode.worldScale = transform.first
                    gltfNode.worldPosition = transform.second
                    gltfNode.worldRotation = transform.third
                    completableFutureNode.complete(gltfNode)
                }
                .exceptionally{throwable ->
                    completableFutureNode.completeExceptionally(throwable)
                    null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
                }

        return completableFutureNode
    }
}

class CustomTransformableNode(transformationSystem: TransformationSystem, objectManagerChannel: MethodChannel, enablePans: Boolean, enableRotation: Boolean) :
    TransformableNode(transformationSystem) { //

    private lateinit var customTranslationController: CustomTranslationController

    private lateinit var customRotationController: CustomRotationController

    init {
        // Remove standard controllers
        translationController.isEnabled = false
        rotationController.isEnabled = false
        scaleController.isEnabled = false
        removeTransformationController(translationController)
        removeTransformationController(rotationController)
        removeTransformationController(scaleController)


        // Add custom controllers if needed
        if (enablePans) {
            customTranslationController = CustomTranslationController(
                this,
                transformationSystem.dragRecognizer,
                objectManagerChannel
            )
            addTransformationController(customTranslationController)
        }
        if (enableRotation) {
            customRotationController = CustomRotationController(
                this,
                transformationSystem.twistRecognizer,
                objectManagerChannel
            )
            addTransformationController(customRotationController)
        }
    }
}

class CustomTranslationController(transformableNode: BaseTransformableNode, gestureRecognizer: DragGestureRecognizer, objectManagerChannel: MethodChannel) :
    TranslationController(transformableNode, gestureRecognizer) {

    val platformChannel: MethodChannel = objectManagerChannel

    override fun canStartTransformation(gesture: DragGesture): Boolean {
        platformChannel.invokeMethod("onPanStart", transformableNode.name)
        super.canStartTransformation(gesture)
        return transformableNode.isSelected
    }

    override fun onContinueTransformation(gesture: DragGesture) {
        platformChannel.invokeMethod("onPanChange", transformableNode.name)
        super.onContinueTransformation(gesture)
        }

    override fun onEndTransformation(gesture: DragGesture) {
        val serializedLocalTransformation = serializeLocalTransformation(transformableNode)
        platformChannel.invokeMethod("onPanEnd", serializedLocalTransformation)
        super.onEndTransformation(gesture)
     }
}

class CustomRotationController(transformableNode: BaseTransformableNode, gestureRecognizer: TwistGestureRecognizer, objectManagerChannel: MethodChannel) :
    RotationController(transformableNode, gestureRecognizer) {

    val platformChannel: MethodChannel = objectManagerChannel

    override fun canStartTransformation(gesture: TwistGesture): Boolean {
        platformChannel.invokeMethod("onRotationStart", transformableNode.name)
        super.canStartTransformation(gesture)
        return transformableNode.isSelected
    }

    override fun onContinueTransformation(gesture: TwistGesture) {
        platformChannel.invokeMethod("onRotationChange", transformableNode.name)
        super.onContinueTransformation(gesture)
    }

    override fun onEndTransformation(gesture: TwistGesture) {
        val serializedLocalTransformation = serializeLocalTransformation(transformableNode)
        platformChannel.invokeMethod("onRotationEnd", serializedLocalTransformation)
        super.onEndTransformation(gesture)
     }
}
