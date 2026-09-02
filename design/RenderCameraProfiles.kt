package design

/**
 * Camera presets for architectural exterior previews.
 */
object RenderCameraProfiles {
    enum class View {
        FRONT,
        CORNER,
        AERIAL,
        WALKAROUND
    }

    fun availableViews(): List<View> = View.entries
}
