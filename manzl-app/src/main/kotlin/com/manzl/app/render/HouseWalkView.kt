package com.manzl.app.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import com.manzl.app.model.FloorPlan
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight native first-person renderer.
 *
 * It intentionally uses only Android/OpenGL ES APIs for milestone 1. This keeps the installed APK
 * independent of servers, API keys, subscriptions and external rendering runtimes while still
 * giving us a GPU-backed path that can later host PBR materials and on-device ML output.
 */
class HouseWalkView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile
    private var pendingMesh: MeshData? = null

    private var walls: GlMesh? = null
    private var floor: GlMesh? = null
    private var shaderProgram = 0
    private var uMvp = -1
    private var uColor = -1

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private var aspect = 1f
    private var cameraX = 0f
    private var cameraY = EYE_HEIGHT
    private var cameraZ = 0f
    private var yaw = 0f
    private var pitch = 0f

    private var previousTouchX = 0f
    private var previousTouchY = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun setFloorPlan(plan: FloorPlan) {
        pendingMesh = HouseMeshBuilder.build(plan)
        queueEvent {
            cameraX = 0f
            cameraY = EYE_HEIGHT
            cameraZ = 0f
            yaw = 0f
            pitch = 0f
        }
    }

    fun moveForward(amountMeters: Float) {
        queueEvent {
            cameraX += sin(yaw) * amountMeters
            cameraZ -= cos(yaw) * amountMeters
        }
    }

    fun strafe(amountMeters: Float) {
        queueEvent {
            cameraX += cos(yaw) * amountMeters
            cameraZ += sin(yaw) * amountMeters
        }
    }

    fun resetCamera() {
        queueEvent {
            cameraX = 0f
            cameraY = EYE_HEIGHT
            cameraZ = 0f
            yaw = 0f
            pitch = 0f
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.90f, 0.92f, 0.94f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(shaderProgram, "uMvp")
        uColor = GLES30.glGetUniformLocation(shaderProgram, "uColor")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingMesh?.let { mesh ->
            pendingMesh = null
            walls?.destroy()
            floor?.destroy()
            walls = uploadMesh(mesh.wallVertices, mesh.wallIndices)
            floor = uploadMesh(mesh.floorVertices, mesh.floorIndices)
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (shaderProgram == 0) return

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, 0.05f, 80f)

        val cosPitch = cos(pitch)
        val lookX = cameraX + sin(yaw) * cosPitch
        val lookY = cameraY + sin(pitch)
        val lookZ = cameraZ - cos(yaw) * cosPitch
        Matrix.setLookAtM(
            view,
            0,
            cameraX,
            cameraY,
            cameraZ,
            lookX,
            lookY,
            lookZ,
            0f,
            1f,
            0f,
        )
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        GLES30.glUseProgram(shaderProgram)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        floor?.let {
            GLES30.glUniform4f(uColor, 0.72f, 0.72f, 0.70f, 1f)
            drawMesh(it)
        }
        walls?.let {
            GLES30.glUniform4f(uColor, 0.96f, 0.955f, 0.94f, 1f)
            drawMesh(it)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousTouchX = event.x
                previousTouchY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - previousTouchX
                val dy = event.y - previousTouchY
                previousTouchX = event.x
                previousTouchY = event.y
                queueEvent {
                    yaw -= dx * LOOK_SENSITIVITY
                    pitch = (pitch - dy * LOOK_SENSITIVITY).coerceIn(-MAX_PITCH, MAX_PITCH)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        queueEvent {
            walls?.destroy()
            floor?.destroy()
            walls = null
            floor = null
            if (shaderProgram != 0) {
                GLES30.glDeleteProgram(shaderProgram)
                shaderProgram = 0
            }
        }
        super.onDetachedFromWindow()
    }

    private fun uploadMesh(vertices: FloatArray, indices: IntArray): GlMesh? {
        if (vertices.isEmpty() || indices.isEmpty()) return null

        val vertexBuffer = FloatBuffer.allocateDirect(vertices.size)
        vertexBuffer.put(vertices).position(0)
        val indexBuffer = IntBuffer.allocateDirect(indices.size)
        indexBuffer.put(indices).position(0)

        val handles = IntArray(2)
        GLES30.glGenBuffers(2, handles, 0)
        val vbo = handles[0]
        val ibo = handles[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            vertexBuffer,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * Int.SIZE_BYTES,
            indexBuffer,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        return GlMesh(vbo = vbo, ibo = ibo, indexCount = indices.size)
    }

    private fun drawMesh(mesh: GlMesh) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(
            0,
            3,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            0,
        )
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(
            1,
            3,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            3 * Float.SIZE_BYTES,
        )
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            mesh.indexCount,
            GLES30.GL_UNSIGNED_INT,
            0,
        )

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        check(status[0] == GLES30.GL_TRUE) {
            "OpenGL program link failed: ${GLES30.glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "OpenGL shader compilation failed: ${GLES30.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private data class GlMesh(
        val vbo: Int,
        val ibo: Int,
        val indexCount: Int,
    ) {
        fun destroy() {
            GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        }
    }

    companion object {
        private const val EYE_HEIGHT = 1.65f
        private const val FIELD_OF_VIEW_DEGREES = 68f
        private const val LOOK_SENSITIVITY = 0.0042f
        private val MAX_PITCH = (80.0 * PI / 180.0).toFloat()
        private const val STRIDE_BYTES = 6 * Float.SIZE_BYTES

        private const val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;

            uniform mat4 uMvp;
            out vec3 vNormal;
            out float vHeight;

            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vNormal = aNormal;
                vHeight = aPosition.y;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            uniform vec4 uColor;
            in vec3 vNormal;
            in float vHeight;
            out vec4 outColor;

            void main() {
                vec3 lightDirection = normalize(vec3(-0.35, 0.88, 0.28));
                float diffuse = max(dot(normalize(vNormal), lightDirection), 0.0);
                float ambient = 0.64;
                float ceilingBounce = clamp(vHeight / 3.0, 0.0, 1.0) * 0.08;
                vec3 lit = uColor.rgb * (ambient + diffuse * 0.34 + ceilingBounce);
                outColor = vec4(lit, uColor.a);
            }
        """
    }
}

private fun FloatBuffer.Companion.allocateDirect(size: Int): FloatBuffer =
    ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

private fun IntBuffer.Companion.allocateDirect(size: Int): IntBuffer =
    ByteBuffer.allocateDirect(size * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
