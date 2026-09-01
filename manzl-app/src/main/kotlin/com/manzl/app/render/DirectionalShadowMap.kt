package com.manzl.app.render

import android.opengl.GLES30
import android.opengl.Matrix

/**
 * One local directional-light depth shadow map for the walkthrough.
 *
 * This intentionally uses one 1024² map rather than cascades: the active window follows the player
 * and is texel-snapped by [ShadowFrustumPlanner], giving useful doorway/façade/interior shadows while
 * keeping memory and fill cost predictable on mid-range Android GPUs. If depth-texture framebuffer
 * creation fails on a device, rendering continues with shadows disabled rather than breaking the
 * walkthrough.
 */
internal class DirectionalShadowMap(
    private val mapSize: Int = ShadowFrustumPlanner.DEFAULT_MAP_SIZE,
) {
    private var framebuffer = 0
    private var depthTexture = 0
    private var depthProgram = 0
    private var uDepthLightMvp = -1
    private var available = false

    private val lightView = FloatArray(16)
    private val lightProjection = FloatArray(16)
    private val lightMvp = FloatArray(16)

    fun initialize(createProgram: (String, String) -> Int) {
        destroy()
        depthProgram = runCatching { createProgram(DEPTH_VERTEX_SHADER, DEPTH_FRAGMENT_SHADER) }
            .getOrDefault(0)
        if (depthProgram == 0) return
        uDepthLightMvp = GLES30.glGetUniformLocation(depthProgram, "uLightMvp")

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        depthTexture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_DEPTH_COMPONENT16,
            mapSize,
            mapSize,
            0,
            GLES30.GL_DEPTH_COMPONENT,
            GLES30.GL_UNSIGNED_SHORT,
            null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        framebuffer = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_TEXTURE_2D,
            depthTexture,
            0,
        )
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        available = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        if (!available) {
            destroy()
        }
    }

    /** Returns true when a valid shadow pass has been started. */
    fun begin(focusX: Float, focusY: Float, focusZ: Float): Boolean {
        if (!available || framebuffer == 0 || depthProgram == 0) return false
        val plan = ShadowFrustumPlanner.plan(focusX, focusY, focusZ, mapSize = mapSize)
        val lightX = plan.focusX + LIGHT_DIRECTION_X * LIGHT_DISTANCE_METERS
        val lightY = plan.focusY + LIGHT_DIRECTION_Y * LIGHT_DISTANCE_METERS
        val lightZ = plan.focusZ + LIGHT_DIRECTION_Z * LIGHT_DISTANCE_METERS

        Matrix.setLookAtM(
            lightView,
            0,
            lightX,
            lightY,
            lightZ,
            plan.focusX,
            plan.focusY,
            plan.focusZ,
            0f,
            1f,
            0f,
        )
        Matrix.orthoM(
            lightProjection,
            0,
            -plan.radiusMeters,
            plan.radiusMeters,
            -plan.radiusMeters,
            plan.radiusMeters,
            SHADOW_NEAR_METERS,
            SHADOW_FAR_METERS,
        )
        Matrix.multiplyMM(lightMvp, 0, lightProjection, 0, lightView, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, mapSize, mapSize)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.15f, 2.0f)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(depthProgram)
        GLES30.glUniformMatrix4fv(uDepthLightMvp, 1, false, lightMvp, 0)
        return true
    }

    fun end(screenWidth: Int, screenHeight: Int) {
        if (!available) return
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenWidth.coerceAtLeast(1), screenHeight.coerceAtLeast(1))
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    fun bindForMainProgram(
        program: Int,
        lightMvpUniformName: String = "uLightMvp",
        samplerUniformName: String = "uShadowMap",
        texelUniformName: String = "uShadowTexelSize",
        enabledUniformName: String = "uShadowsEnabled",
    ) {
        val enabled = available && depthTexture != 0
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(program, lightMvpUniformName),
            1,
            false,
            lightMvp,
            0,
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, texelUniformName),
            if (enabled) 1f / mapSize.toFloat() else 0f,
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, enabledUniformName),
            if (enabled) 1f else 0f,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + SHADOW_TEXTURE_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (enabled) depthTexture else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, samplerUniformName), SHADOW_TEXTURE_UNIT)
    }

    fun destroy() {
        available = false
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        if (depthTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(depthTexture), 0)
            depthTexture = 0
        }
        if (depthProgram != 0) {
            GLES30.glDeleteProgram(depthProgram)
            depthProgram = 0
        }
        uDepthLightMvp = -1
    }

    companion object {
        // Keep this exactly aligned with the main PBR shader's warm daylight direction.
        const val LIGHT_DIRECTION_X = -0.34f
        const val LIGHT_DIRECTION_Y = 0.88f
        const val LIGHT_DIRECTION_Z = 0.31f

        private const val LIGHT_DISTANCE_METERS = 27f
        private const val SHADOW_NEAR_METERS = 1.0f
        private const val SHADOW_FAR_METERS = 70f
        private const val SHADOW_TEXTURE_UNIT = 3

        private const val DEPTH_VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uLightMvp;
            void main() {
                gl_Position = uLightMvp * vec4(aPosition, 1.0);
            }
        """

        private const val DEPTH_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            out vec4 outColor;
            void main() {
                outColor = vec4(1.0);
            }
        """
    }
}
