package com.manzl.app.render

import com.manzl.app.design.ReferenceDrivenDesignEngine
import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorSwingSide

/**
 * Stacks independently measured floor-plan meshes at their declared base elevations.
 *
 * No X/Z registration correction is applied here. If two source drawings disagree, their measured
 * geometry remains untouched; StairLevelLinker may describe a semantic connection but cannot move
 * either floor. This keeps the same geometry-authoritative rule used by the single-level pipeline.
 *
 * Verified structural columns are appended as solid prisms to the same structural material stream as
 * measured walls. Door leaves are deliberately suppressed from the static mesh. Frames remain static,
 * while known physical leaves are rendered by DoorLeafMeshBuilder from InteractiveDoorWorld so
 * animation and collision share the exact same runtime pose.
 */
internal object BuildingMeshBuilder {

    fun build(building: BuildingPlan): MeshData {
        var combined = emptyMeshData()
        for (level in building.levels.sortedBy { it.levelIndex }) {
            val design = ReferenceDrivenDesignEngine.synthesize(level.plan)
            val staticPlan = level.plan.copy(
                doors = level.plan.doors.map { door ->
                    door.copy(
                        hingeSide = DoorHingeSide.UNKNOWN,
                        swingSide = DoorSwingSide.UNKNOWN,
                        swingConfidence = 0f,
                    )
                }
            )
            val houseMesh = HouseMeshBuilder.build(
                plan = staticPlan,
                wallHeightOverride = design.wallHeightMeters,
                doorHeightOverride = design.doorHeightMeters,
            )
            val columnMesh = StructuralColumnMeshBuilder.build(
                plan = staticPlan,
                heightOverrideMeters = design.wallHeightMeters,
            )
            val levelMesh = houseMesh
                .append(columnMesh)
                .translatedY(level.baseElevationMeters)
            combined = combined.append(levelMesh)
        }
        return combined
    }
}

internal fun MeshData.translatedY(offsetMeters: Float): MeshData {
    if (offsetMeters == 0f) return this
    return copy(
        wallVertices = wallVertices.translateVerticesY(offsetMeters),
        floorVertices = floorVertices.translateVerticesY(offsetMeters),
        ceilingVertices = ceilingVertices.translateVerticesY(offsetMeters),
        trimVertices = trimVertices.translateVerticesY(offsetMeters),
        glassVertices = glassVertices.translateVerticesY(offsetMeters),
    )
}

internal fun MeshData.append(other: MeshData): MeshData = MeshData(
    wallVertices = wallVertices + other.wallVertices,
    wallIndices = wallIndices.appendIndices(other.wallIndices, wallVertices.vertexCount()),
    floorVertices = floorVertices + other.floorVertices,
    floorIndices = floorIndices.appendIndices(other.floorIndices, floorVertices.vertexCount()),
    ceilingVertices = ceilingVertices + other.ceilingVertices,
    ceilingIndices = ceilingIndices.appendIndices(other.ceilingIndices, ceilingVertices.vertexCount()),
    trimVertices = trimVertices + other.trimVertices,
    trimIndices = trimIndices.appendIndices(other.trimIndices, trimVertices.vertexCount()),
    glassVertices = glassVertices + other.glassVertices,
    glassIndices = glassIndices.appendIndices(other.glassIndices, glassVertices.vertexCount()),
)

private fun FloatArray.translateVerticesY(offsetMeters: Float): FloatArray {
    if (isEmpty()) return this
    val result = copyOf()
    var index = 0
    while (index + FLOATS_PER_VERTEX <= result.size) {
        result[index + 1] += offsetMeters
        index += FLOATS_PER_VERTEX
    }
    return result
}

private fun FloatArray.vertexCount(): Int = size / FLOATS_PER_VERTEX

private fun IntArray.appendIndices(other: IntArray, vertexOffset: Int): IntArray {
    if (other.isEmpty()) return this
    val shifted = IntArray(other.size) { index -> other[index] + vertexOffset }
    return this + shifted
}

private fun emptyMeshData(): MeshData = MeshData(
    wallVertices = floatArrayOf(),
    wallIndices = intArrayOf(),
    floorVertices = floatArrayOf(),
    floorIndices = intArrayOf(),
    ceilingVertices = floatArrayOf(),
    ceilingIndices = intArrayOf(),
    trimVertices = floatArrayOf(),
    trimIndices = intArrayOf(),
    glassVertices = floatArrayOf(),
    glassIndices = intArrayOf(),
)

private const val FLOATS_PER_VERTEX = 6
