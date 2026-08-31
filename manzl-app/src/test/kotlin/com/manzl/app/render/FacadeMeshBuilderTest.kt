package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacadeMeshBuilderTest {

    @Test
    fun `exterior room wall receives facade skin`() {
        val wall = WallSegment(Vec2(-2f, -2f), Vec2(2f, -2f))
        val mesh = FacadeMeshBuilder.build(plan(walls = listOf(wall), rooms = listOf(fullRoom())))

        assertTrue(mesh.vertices.isNotEmpty())
        assertTrue(mesh.indices.isNotEmpty())
        assertTrue(hasNegativeZFacingNormal(mesh.vertices))
    }

    @Test
    fun `interior partition receives no facade skin`() {
        val partition = WallSegment(Vec2(0f, -2f), Vec2(0f, 2f))
        val left = RoomRegion(
            id = "left",
            polygon = listOf(Vec2(-2f, -2f), Vec2(0f, -2f), Vec2(0f, 2f), Vec2(-2f, 2f)),
            label = "صالة",
            confidence = 0.95f,
        )
        val right = RoomRegion(
            id = "right",
            polygon = listOf(Vec2(0f, -2f), Vec2(2f, -2f), Vec2(2f, 2f), Vec2(0f, 2f)),
            label = "مجلس",
            confidence = 0.95f,
        )

        val mesh = FacadeMeshBuilder.build(plan(walls = listOf(partition), rooms = listOf(left, right)))
        assertTrue(mesh.vertices.isEmpty())
        assertTrue(mesh.indices.isEmpty())
    }

    @Test
    fun `door opening remains absent from facade below lintel`() {
        val wall = WallSegment(Vec2(-2f, -2f), Vec2(2f, -2f), heightMeters = 3f)
        val door = DoorOpening(
            center = Vec2(0f, -2f),
            widthMeters = 1f,
            rotationDegrees = 0f,
            confidence = 0.95f,
        )
        val mesh = FacadeMeshBuilder.build(
            plan(walls = listOf(wall), rooms = listOf(fullRoom()), doors = listOf(door)),
            doorHeightOverride = 2.2f,
        )

        assertTrue(mesh.vertices.isNotEmpty())
        assertFalse(hasTriangleCentroidInsideDoorVoid(mesh, halfWidth = 0.49f, maxY = 2.19f))
    }

    @Test
    fun `window receives projected surround with real side normals`() {
        val wall = WallSegment(Vec2(-2f, -2f), Vec2(2f, -2f), heightMeters = 3f)
        val window = WindowOpening(
            center = Vec2(0f, -2f),
            widthMeters = 1.4f,
            rotationDegrees = 0f,
            sillHeightMeters = 0.9f,
            heightMeters = 1.25f,
            confidence = 0.95f,
        )
        val mesh = FacadeMeshBuilder.build(
            plan(walls = listOf(wall), rooms = listOf(fullRoom()), windows = listOf(window)),
        )

        assertTrue(mesh.vertices.isNotEmpty())
        assertTrue(hasProjectedSurroundSideNormal(mesh.vertices))
        assertFalse(hasTriangleCentroidInsideWindowVoid(mesh, halfWidth = 0.69f, minY = 0.91f, maxY = 2.14f))
    }

    @Test
    fun `roomless envelope fallback is not styled at default confidence gate`() {
        val boundary = WallSegment(Vec2(-3f, -3f), Vec2(3f, -3f))
        val mesh = FacadeMeshBuilder.build(plan(walls = listOf(boundary), rooms = emptyList()))
        assertTrue(mesh.vertices.isEmpty())
    }

    private fun fullRoom() = RoomRegion(
        id = "living",
        polygon = listOf(Vec2(-2f, -2f), Vec2(2f, -2f), Vec2(2f, 2f), Vec2(-2f, 2f)),
        label = "صالة",
        confidence = 0.95f,
    )

    private fun plan(
        walls: List<WallSegment>,
        rooms: List<RoomRegion>,
        doors: List<DoorOpening> = emptyList(),
        windows: List<WindowOpening> = emptyList(),
    ) = FloorPlan(
        widthMeters = 6f,
        depthMeters = 6f,
        walls = walls,
        doors = doors,
        windows = windows,
        rooms = rooms,
        analysisConfidence = 0.9f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )

    private fun hasNegativeZFacingNormal(vertices: FloatArray): Boolean {
        var i = 0
        while (i + 5 < vertices.size) {
            if (vertices[i + 5] < -0.9f) return true
            i += 6
        }
        return false
    }

    private fun hasProjectedSurroundSideNormal(vertices: FloatArray): Boolean {
        var i = 0
        while (i + 5 < vertices.size) {
            val nx = vertices[i + 3]
            val ny = vertices[i + 4]
            if (kotlin.math.abs(nx) > 0.9f || kotlin.math.abs(ny) > 0.9f) return true
            i += 6
        }
        return false
    }

    private fun hasTriangleCentroidInsideDoorVoid(
        mesh: FacadeMesh,
        halfWidth: Float,
        maxY: Float,
    ): Boolean = hasTriangleCentroidInsideRect(mesh, halfWidth, 0.01f, maxY)

    private fun hasTriangleCentroidInsideWindowVoid(
        mesh: FacadeMesh,
        halfWidth: Float,
        minY: Float,
        maxY: Float,
    ): Boolean = hasTriangleCentroidInsideRect(mesh, halfWidth, minY, maxY)

    private fun hasTriangleCentroidInsideRect(
        mesh: FacadeMesh,
        halfWidth: Float,
        minY: Float,
        maxY: Float,
    ): Boolean {
        for (i in mesh.indices.indices step 3) {
            if (i + 2 >= mesh.indices.size) break
            val a = mesh.indices[i] * 6
            val b = mesh.indices[i + 1] * 6
            val c = mesh.indices[i + 2] * 6
            val cx = (mesh.vertices[a] + mesh.vertices[b] + mesh.vertices[c]) / 3f
            val cy = (mesh.vertices[a + 1] + mesh.vertices[b + 1] + mesh.vertices[c + 1]) / 3f
            if (cx > -halfWidth && cx < halfWidth && cy > minY && cy < maxY) return true
        }
        return false
    }
}
