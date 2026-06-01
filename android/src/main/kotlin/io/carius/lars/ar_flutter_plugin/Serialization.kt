package io.carius.lars.ar_flutter_plugin

import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import kotlin.math.sqrt

// =============================================================================
// Serialization helpers for the SceneView 4.16.x migration (#92).
//
// The old implementation lived under Serialization/Serializers.kt and
// Serialization/Deserializers.kt and depended on Sceneform math types
// (Vector3 / Quaternion). Sceneform is gone, so these are rewritten against
// pure ARCore + FloatArray math. The on-the-wire contract is UNCHANGED so the
// Dart side (ARHitTestResult / MatrixConverter) keeps working without edits:
//   - pose         -> column-major float[16] as a DoubleArray (OpenGL layout,
//                     matches Dart vector_math Matrix4.fromList storage)
//   - hit result   -> { type:Int(plane=1/point=2/undefined=0),
//                        distance:Double, worldTransform:DoubleArray(16) }
// =============================================================================

/** Serializes an ARCore [Pose] to a column-major 4x4 matrix as a [DoubleArray]. */
internal fun serializePose(pose: Pose): DoubleArray {
    val matrix = FloatArray(16)
    pose.toMatrix(matrix, 0)
    return DoubleArray(16) { matrix[it].toDouble() }
}

/** Serializes an ARCore [HitResult] into the map shape the Dart side expects. */
internal fun serializeHitResult(hit: HitResult): HashMap<String, Any> {
    val serialized = HashMap<String, Any>()
    val trackable = hit.trackable
    serialized["type"] = when {
        trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) -> 1 // plane
        trackable is Point -> 2 // feature point
        else -> 0 // undefined
    }
    serialized["distance"] = hit.distance.toDouble()
    serialized["worldTransform"] = serializePose(hit.hitPose)
    return serialized
}

/**
 * Rebuilds an ARCore [Pose] from a column-major 4x4 transform sent by the Dart
 * side. Translation is the 4th column; rotation is decomposed to a quaternion
 * after stripping per-column scale.
 *
 * NOTE: the old Sceneform deserializer applied an extra 180° z/y correction to
 * bridge Sceneform's model coordinate convention. That is intentionally dropped
 * here: the matrix originates from an ARCore pose we serialized ourselves, so
 * the round-trip ARCore->matrix->ARCore needs no coordinate correction.
 */
internal fun deserializePose(transform: List<Double>): Pose {
    val m = FloatArray(16) { transform[it].toFloat() }

    // Column-major: the 4th column (indices 12,13,14) holds the translation.
    val translation = floatArrayOf(m[12], m[13], m[14])

    // Per-column lengths give the scale of each basis axis.
    val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
    val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
    val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])

    // rIJ = element at row I, column J of the scale-normalized rotation matrix.
    val r00 = m[0] / sx; val r10 = m[1] / sx; val r20 = m[2] / sx
    val r01 = m[4] / sy; val r11 = m[5] / sy; val r21 = m[6] / sy
    val r02 = m[8] / sz; val r12 = m[9] / sz; val r22 = m[10] / sz

    var x: Float; var y: Float; var z: Float; var w: Float
    val trace = r00 + r11 + r22
    if (trace > 0f) {
        val s = sqrt((trace + 1.0).toFloat()) * 2f
        w = 0.25f * s
        x = (r21 - r12) / s
        y = (r02 - r20) / s
        z = (r10 - r01) / s
    } else if (r00 > r11 && r00 > r22) {
        val s = sqrt((1.0 + r00 - r11 - r22).toFloat()) * 2f
        w = (r21 - r12) / s
        x = 0.25f * s
        y = (r01 + r10) / s
        z = (r02 + r20) / s
    } else if (r11 > r22) {
        val s = sqrt((1.0 + r11 - r00 - r22).toFloat()) * 2f
        w = (r02 - r20) / s
        x = (r01 + r10) / s
        y = 0.25f * s
        z = (r12 + r21) / s
    } else {
        val s = sqrt((1.0 + r22 - r00 - r11).toFloat()) * 2f
        w = (r10 - r01) / s
        x = (r02 + r20) / s
        y = (r12 + r21) / s
        z = 0.25f * s
    }

    return Pose(translation, floatArrayOf(x, y, z, w))
}
