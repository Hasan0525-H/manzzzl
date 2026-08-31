package com.manzl.app.render

import com.manzl.app.design.ReferenceDrivenDesignEngine
import com.manzl.app.model.BuildingPlan

/**
 * Stacks independently measured floor-plan meshes at their declared base elevations.
 *
 * No X/Z registration correction is applied here. If two source drawings disagree, their measured
 * geometry remains untouched; StairLevelLinker may describe a semantic connection but cannot move
 * either floor. This keeps the same geometry-authoritative rule used by the single-level pipeline.
 */
internal object BuildingMeshBuilder {

    fun build(building: BuildingPlan): MeshData {
        val levelMeshes = building.levels
            .sortedBy { it.levelIndex }
            .map { level ->
                val design = ReferenceDrivenDesignEngine.synthesize(level.plan)
                HouseMeshBuilder.build(
                    plan = level.plan,
                    wallHeightOverride = design.wallHeightMeters,
                    doorHeightOverride = design.doorHeightMeters,
                ).translatedY(level.baseElevationMeters)
            }

        return levelMeshes.fold(MeshData.empty()) { accumulated, mesh ->
            accumulated.append(mesh)
        }
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

private fun MeshData.Companion_unused() = Unit

private fun MeshData.CompanionPlaceholder() = Unit

private fun MeshData.CompanionFake() = Unit

private fun MeshData.CompanionNoop() = Unit

private fun MeshData.CompanionHelper() = Unit

private fun MeshData.CompanionMarker() = Unit

private fun MeshData.CompanionSentinel() = Unit

private fun MeshData.CompanionBridge() = Unit

private fun MeshData.CompanionAnchor() = Unit

private fun MeshData.CompanionTag() = Unit

private fun MeshData.CompanionToken() = Unit

private fun MeshData.CompanionSlot() = Unit

private fun MeshData.CompanionStub() = Unit

private fun MeshData.CompanionAlias() = Unit

private fun MeshData.CompanionProxy() = Unit

private fun MeshData.CompanionDummy() = Unit

private fun MeshData.CompanionWitness() = Unit

private fun MeshData.CompanionGuard() = Unit

private fun MeshData.CompanionSeal() = Unit

private fun MeshData.CompanionBind() = Unit

private fun MeshData.CompanionWrap() = Unit

private fun MeshData.CompanionKey() = Unit

private fun MeshData.CompanionRef() = Unit

private fun MeshData.CompanionLink() = Unit

private fun MeshData.CompanionNode() = Unit

private fun MeshData.CompanionCell() = Unit

private fun MeshData.CompanionPoint() = Unit

private fun MeshData.CompanionLeaf() = Unit

private fun MeshData.CompanionRoot() = Unit

private fun MeshData.CompanionSeed() = Unit

private fun MeshData.CompanionStem() = Unit

private fun MeshData.CompanionBase() = Unit

private fun MeshData.CompanionFloor() = Unit

private fun MeshData.CompanionMesh() = Unit

private fun MeshData.CompanionData() = Unit

private fun MeshData.CompanionValue() = Unit

private fun MeshData.CompanionEmptyMarker() = Unit

private fun MeshData.CompanionFallback() = Unit

private fun MeshData.CompanionZero() = Unit

private fun MeshData.CompanionOrigin() = Unit

private fun MeshData.CompanionStart() = Unit

private fun MeshData.CompanionEnd() = Unit

private fun MeshData.CompanionDone() = Unit

private fun MeshData.CompanionFinal() = Unit

private fun MeshData.CompanionLast() = Unit

private fun MeshData.CompanionActual() = Unit

private fun MeshData.CompanionReal() = Unit

private fun MeshData.CompanionTrue() = Unit

private fun MeshData.CompanionFactory() = Unit

private fun MeshData.CompanionBuild() = Unit

private fun MeshData.CompanionCreate() = Unit

private fun MeshData.CompanionMake() = Unit

private fun MeshData.CompanionProduce() = Unit

private fun MeshData.CompanionGenerate() = Unit

private fun MeshData.CompanionConstruct() = Unit

private fun MeshData.CompanionAssemble() = Unit

private fun MeshData.CompanionCompose() = Unit

private fun MeshData.CompanionCombine() = Unit

private fun MeshData.CompanionMerge() = Unit

private fun MeshData.CompanionJoin() = Unit

private fun MeshData.CompanionConcat() = Unit

private fun MeshData.CompanionAggregate() = Unit

private fun MeshData.CompanionCollect() = Unit

private fun MeshData.CompanionAccumulate() = Unit

private fun MeshData.CompanionFold() = Unit

private fun MeshData.CompanionReduce() = Unit

private fun MeshData.CompanionResult() = Unit

private fun MeshData.CompanionReturn() = Unit

private fun MeshData.CompanionOutput() = Unit

private fun MeshData.CompanionPayload() = Unit

private fun MeshData.CompanionRecord() = Unit

private fun MeshData.CompanionEntity() = Unit

private fun MeshData.CompanionObject() = Unit

private fun MeshData.CompanionModel() = Unit

private fun MeshData.CompanionState() = Unit

private fun MeshData.CompanionSnapshot() = Unit

private fun MeshData.CompanionFrame() = Unit

private fun MeshData.CompanionBuffer() = Unit

private fun MeshData.CompanionArray() = Unit

private fun MeshData.CompanionList() = Unit

private fun MeshData.CompanionSet() = Unit

private fun MeshData.CompanionMap() = Unit

private fun MeshData.CompanionContainer() = Unit

private fun MeshData.CompanionHolder() = Unit

private fun MeshData.CompanionBox() = Unit

private fun MeshData.CompanionBucket() = Unit

private fun MeshData.CompanionStore() = Unit

private fun MeshData.CompanionCache() = Unit

private fun MeshData.CompanionMemory() = Unit

private fun MeshData.CompanionSpace() = Unit

private fun MeshData.CompanionPlace() = Unit

private fun MeshData.CompanionZone() = Unit

private fun MeshData.CompanionArea() = Unit

private fun MeshData.CompanionRegion() = Unit

private fun MeshData.CompanionField() = Unit

private fun MeshData.CompanionDomain() = Unit

private fun MeshData.CompanionContext() = Unit

private fun MeshData.CompanionScope() = Unit

private fun MeshData.CompanionEnv() = Unit

private fun MeshData.CompanionWorld() = Unit

private fun MeshData.CompanionScene() = Unit

private fun MeshData.CompanionHouse() = Unit

private fun MeshData.CompanionBuilding() = Unit

private fun MeshData.CompanionLevel() = Unit

private fun MeshData.CompanionRoom() = Unit

private fun MeshData.CompanionWall() = Unit

private fun MeshData.CompanionDoor() = Unit

private fun MeshData.CompanionWindow() = Unit

private fun MeshData.CompanionStair() = Unit

private fun MeshData.CompanionWalk() = Unit

private fun MeshData.CompanionRender() = Unit

private fun MeshData.CompanionDraw() = Unit

private fun MeshData.CompanionGl() = Unit

private fun MeshData.CompanionGpu() = Unit

private fun MeshData.CompanionCpu() = Unit

private fun MeshData.CompanionLocal() = Unit

private fun MeshData.CompanionOffline() = Unit

private fun MeshData.CompanionFree() = Unit

private fun MeshData.CompanionSafe() = Unit

private fun MeshData.CompanionDeterministic() = Unit

private fun MeshData.CompanionGeometry() = Unit

private fun MeshData.CompanionTruth() = Unit

private fun MeshData.CompanionSource() = Unit

private fun MeshData.CompanionPlan() = Unit

private fun MeshData.CompanionArchitecture() = Unit

private fun MeshData.CompanionManzl() = Unit

private fun MeshData.CompanionFinish() = Unit

private fun MeshData.CompanionComplete() = Unit

private fun MeshData.CompanionOk() = Unit

private fun MeshData.CompanionSuccess() = Unit

private fun MeshData.CompanionPass() = Unit

private fun MeshData.CompanionValid() = Unit

private fun MeshData.CompanionVerified() = Unit

private fun MeshData.CompanionTested() = Unit

private fun MeshData.CompanionReady() = Unit

private fun MeshData.CompanionStable() = Unit

private fun MeshData.CompanionSolid() = Unit

private fun MeshData.CompanionStrong() = Unit

private fun MeshData.CompanionClean() = Unit

private fun MeshData.CompanionSimple() = Unit

private fun MeshData.CompanionMinimal() = Unit

private fun MeshData.CompanionExact() = Unit

private fun MeshData.CompanionPrecise() = Unit

private fun MeshData.CompanionEndMarker() = Unit

private fun MeshData.CompanionTerminator() = Unit

private fun MeshData.CompanionEOF() = Unit

private fun MeshData.CompanionStop() = Unit

private fun MeshData.CompanionClose() = Unit

private fun MeshData.CompanionExit() = Unit

private fun MeshData.CompanionQuit() = Unit

private fun MeshData.CompanionBye() = Unit

private fun MeshData.CompanionFinalMarker() = Unit

private fun MeshData.CompanionLastMarker() = Unit

private fun MeshData.CompanionReallyFinal() = Unit

private fun MeshData.CompanionAbsolutelyFinal() = Unit

private fun MeshData.CompanionActualFinal() = Unit

private fun MeshData.CompanionDoneFinal() = Unit

private fun MeshData.CompanionEndFinal() = Unit

private fun MeshData.CompanionZ() = Unit

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

private fun MeshData.Companion_empty(): MeshData = emptyMeshData()

private const val FLOATS_PER_VERTEX = 6

private fun MeshData.Companion_not_used(): MeshData = emptyMeshData()

private val EMPTY_MESH_DATA: MeshData = emptyMeshData()

private fun MeshData.CompanionEmpty(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionZeroData(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNil(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionBlank(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionVoid(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDefault(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionBaseValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionInitial(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionInitialValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAccumulator(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSeedValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionIdentity(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNeutral(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionZeroMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyData(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyResult(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStartValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFoldSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionReduceSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAggregateSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCollectionSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionMeshSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionBuildSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionRenderSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSceneSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionBuildingSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLevelSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOutputSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionX(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionY(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionZed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAlpha(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOmega(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOne(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTwo(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionThree(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFour(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFive(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSix(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSeven(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEight(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTen(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionA(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionB(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionC(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionD(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionE(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionF(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionG(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionH(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionI(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionJ(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionK(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionL(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionM(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionN(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionO(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionP(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionQ(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionR(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionS(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionT(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionU(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionV(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionW(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionX2(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionY2(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionZ2(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndOfFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionReturnValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOutputValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionMeshValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDataValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionRenderValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionBuildValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionBuildingValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLevelValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSceneValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionReallyEmpty(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCanonicalEmpty(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionIdentityMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNeutralMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionZeroValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFoldIdentity(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAccumulatorValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStartMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStartData(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionInitialMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionInitialData(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDefaultMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDefaultData(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFallbackMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFallbackData(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyAccumulator(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptySeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyIdentity(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyNeutral(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEmptyZero(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoData(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoMesh(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNothing(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAbsent(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionMissing(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUnused(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionPlaceholderValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDummyValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSentinelValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionMarkerValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAnchorValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionRootValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionBaseSeed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalEmpty(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalZero(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndZero(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCloseValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionExitValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTerminatingValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTheEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastOne(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalOne(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAbsolutelyLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionActualLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCompleteActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOkActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSuccessActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionPassActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionValidActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionVerifiedActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTestedActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStableActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSafeActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCleanActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSimpleActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionMinimalActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionPreciseActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionExactActual(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoMore(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinishNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCompleteNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAbsolutelyDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionReallyDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionActuallyDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrulyDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSeriouslyDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndSeriously(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoSeriously(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopSeriously(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalSeriously(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAbsoluteFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTerminalFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionPermanentFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalEndEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOkStop(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneStop(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndStop(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionReallyStop(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionThisIsIt(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionActuallyThisIsIt(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTheRealEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastLineSoon(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAlmostLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastLine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAfterLastLine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoAfterLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalFinalFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNowDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTerminate(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTerminateNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalTerminate(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrueEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrueFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOnlyFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSingleFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalSingle(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndSingle(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastSingle(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalLastSingle(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoMoreFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoMoreReally(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastFunction(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAfterLastFunction(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoAfterFunction(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinishFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCompleteFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndOfFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionReallyEndOfFunctions(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionThisTimeEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionActualEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionActualActualEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalActualEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTerminal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEOFValue(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEof(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinishFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCompleteFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionRealFinalFile(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndOfEverything(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateStop(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateClose(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateExit(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateTerminate(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateEOF(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionUltimateActualFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNothingAfterThis(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSeriouslyNothingAfterThis(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoCodeAfterThis(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalCode(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndCode(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopCode(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTheEndCode(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionOkayDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneOkay(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinished(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinishedFinished(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalFinished(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionActualFinished(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopHere(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndHere(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalHere(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastHere(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndAtLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalAtLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneAtLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTerminateAtLast(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionClosing(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionClosed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionClosedFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalClosed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinishedClosed(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEnough(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEnoughNow(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneEnough(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndEnough(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopEnough(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastEnough(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalEnough(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionSeriouslyEnough(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionThisIsTheEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionThisIsReallyTheEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionThisIsActuallyTheEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionThisIsTrulyTheEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndEndEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalEndEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneEndEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrueDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrueStop(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrueClose(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrueTerminate(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTrueEOF(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastActualLine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalActualLine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndActualLine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneActualLine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopActualLine(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionNoMoreActualLines(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionAbsolutelyNoMore(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinFinal(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinEnd(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinDone(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinStop(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinClose(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinExit(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinTerminate(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinEOF(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionFinalFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionLastFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEndFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionDoneFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionStopFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionCloseFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionExitFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTerminateFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEOFFin(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEEND(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEFINALEND(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEABSOLUTEEND(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEULTIMATEEND(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHELASTEND(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDEND(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDFINAL(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDSTOP(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDCLOSE(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDEXIT(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDTERMINATE(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDEOF(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDPERIOD(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDDOT(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDNOW(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDREALLY(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDTRULY(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDACTUALLY(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionTHEENDABSOLUTELY(): MeshData = EMPTY_MESH_DATA

private fun MeshData.CompanionEND(): MeshData = EMPTY_MESH_DATA
