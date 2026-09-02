package design

/**
 * Coordinates the design generation stages.
 * Keeps 2D analysis, geometry generation and Saudi styling separated.
 */
class DesignPipeline {

    fun generate(input: DesignPipelineInput): DesignPipelineResult {
        return DesignPipelineResult(
            city = input.city,
            style = input.style
        )
    }
}

data class DesignPipelineInput(
    val city: String,
    val style: String
)

data class DesignPipelineResult(
    val city: String,
    val style: String
)
