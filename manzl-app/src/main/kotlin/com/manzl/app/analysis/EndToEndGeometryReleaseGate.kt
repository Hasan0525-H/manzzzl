package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityStatus
import java.util.Locale

/**
 * Release evidence built only from the same fail-closed gates used by the production 2D -> 3D path.
 *
 * This object deliberately does not duplicate any numeric threshold. GeometryFidelityEvaluator,
 * GeometryQualityGate and ReconstructionReadinessGate remain the only threshold authorities. The
 * report is a stable, privacy-safe attestation that can be exported for a held-out real-plan corpus
 * and verified by the research release pipeline without teaching Python a second policy.
 */
internal object EndToEndGeometryReleaseGate {

    data class Report(
        val fidelityStatus: GeometryFidelityStatus,
        val fidelityScore: Float,
        val wallCoverage: Float,
        val wallPrecision: Float,
        val endpointSupport: Float,
        val geometryQualityGatePassed: Boolean,
        val reconstructionReadinessGatePassed: Boolean,
        val topologyNearMissCount: Int,
        val unresolvedOpeningCount: Int,
        val unsupportedVerticalVoidCount: Int,
        val unsupportedRoomBoundaryCount: Int,
        val trustedRoomCoverage: Float,
        val trustedRoomCount: Int,
    ) {
        val passed: Boolean
            get() = fidelityStatus == GeometryFidelityStatus.PASS &&
                geometryQualityGatePassed &&
                reconstructionReadinessGatePassed

        /**
         * Stable JSON intentionally contains no source path, filename, raw raster hash or user label.
         * [sampleId] is the opaque corpus id already used by materialized held-out split filenames.
         */
        fun toEvidenceJson(sampleId: String): String {
            require(OPAQUE_SAMPLE_ID.matches(sampleId)) {
                "release evidence requires opaque sample-<32 hex> id"
            }
            return buildString {
                append("{\n")
                append("  \"schema\": 1,\n")
                append("  \"pipeline\": \"manzl-runtime-end-to-end-geometry-gates\",\n")
                append("  \"sampleId\": \"").append(sampleId).append("\",\n")
                append("  \"sourcePathsStored\": false,\n")
                append("  \"sourceFilenamesStored\": false,\n")
                append("  \"rawRasterHashesStored\": false,\n")
                append("  \"runtimeThresholdsDuplicated\": false,\n")
                append("  \"fidelityStatus\": \"").append(fidelityStatus.name).append("\",\n")
                append("  \"fidelityScore\": ").append(jsonNumber(fidelityScore)).append(",\n")
                append("  \"wallCoverage\": ").append(jsonNumber(wallCoverage)).append(",\n")
                append("  \"wallPrecision\": ").append(jsonNumber(wallPrecision)).append(",\n")
                append("  \"endpointSupport\": ").append(jsonNumber(endpointSupport)).append(",\n")
                append("  \"geometryFidelityPass\": ")
                    .append(fidelityStatus == GeometryFidelityStatus.PASS).append(",\n")
                append("  \"geometryQualityGatePassed\": ").append(geometryQualityGatePassed).append(",\n")
                append("  \"reconstructionReadinessGatePassed\": ")
                    .append(reconstructionReadinessGatePassed).append(",\n")
                append("  \"topologyNearMissCount\": ").append(topologyNearMissCount).append(",\n")
                append("  \"unresolvedOpeningCount\": ").append(unresolvedOpeningCount).append(",\n")
                append("  \"unsupportedVerticalVoidCount\": ").append(unsupportedVerticalVoidCount).append(",\n")
                append("  \"unsupportedRoomBoundaryCount\": ").append(unsupportedRoomBoundaryCount).append(",\n")
                append("  \"trustedRoomCoverage\": ").append(jsonNumber(trustedRoomCoverage)).append(",\n")
                append("  \"trustedRoomCount\": ").append(trustedRoomCount).append(",\n")
                append("  \"endToEnd2dTo3dGeometryGatesPassed\": ").append(passed).append(",\n")
                append("  \"releaseReady\": false\n")
                append("}\n")
            }
        }
    }

    fun evaluate(plan: FloorPlan): Report {
        val fidelity = plan.geometryFidelity
        val readiness = ReconstructionReadinessGate.evaluate(plan)
        return Report(
            fidelityStatus = fidelity.status,
            fidelityScore = fidelity.score,
            wallCoverage = fidelity.wallCoverage,
            wallPrecision = fidelity.wallPrecision,
            endpointSupport = fidelity.endpointSupport,
            geometryQualityGatePassed = GeometryQualityGate.isReadyFor3d(plan),
            reconstructionReadinessGatePassed = readiness.ready,
            topologyNearMissCount = WallTopologyIntegrity.findNearMissJunctions(plan).size,
            unresolvedOpeningCount = readiness.unresolvedOpenings.size,
            unsupportedVerticalVoidCount = readiness.unsupportedVerticalVoids.size,
            unsupportedRoomBoundaryCount = readiness.unsupportedRoomBoundaries.size,
            trustedRoomCoverage = readiness.trustedRoomCoverage,
            trustedRoomCount = readiness.trustedRoomCount,
        )
    }

    private fun jsonNumber(value: Float): String {
        require(value.isFinite()) { "release evidence cannot contain non-finite geometry metrics" }
        return String.format(Locale.US, "%.8f", value)
    }

    private val OPAQUE_SAMPLE_ID = Regex("^sample-[0-9a-f]{32}$")
}
