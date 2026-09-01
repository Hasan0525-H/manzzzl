package com.manzl.app.render

import com.manzl.app.design.HouseRenderProfile
import com.manzl.app.design.ReferenceDrivenDesignEngine
import com.manzl.app.model.BuildingPlan

/**
 * CPU-side scene assembly shared by the Android renderer and JVM tests.
 *
 * Static architectural geometry and exterior finish geometry are kept in separate meshes. This is
 * deliberate: the measured wall remains the canonical collision/topology surface, while the façade
 * skin can use a different Saudi stone/plaster material without leaking that finish onto the
 * interior face of the same wall.
 */
internal object WalkthroughSceneAssembler {

    fun build(building: BuildingPlan): WalkthroughScene {
        require(building.levels.isNotEmpty()) { "Building must contain at least one level" }
        val firstLevel = building.levels.minBy { it.levelIndex }
        return WalkthroughScene(
            staticMesh = BuildingMeshBuilder.build(building),
            facadeMesh = BuildingFacadeMeshBuilder.build(building),
            primaryDesign = ReferenceDrivenDesignEngine.synthesize(firstLevel.plan),
        )
    }
}

internal data class WalkthroughScene(
    val staticMesh: MeshData,
    val facadeMesh: FacadeMesh,
    val primaryDesign: HouseRenderProfile,
)
