package com.manzl.app.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import com.manzl.app.design.HouseRenderProfile
import com.manzl.app.design.ReferenceDrivenDesignEngine
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native first-person walkthrough renderer.
 *
 * Rendering, movement, collision and visual-profile synthesis all run locally on the phone. The
 * left side of the surface is a dynamic analog stick (touch and drag); the right side controls
 * free-look. Two fingers can be used simultaneously, matching modern mobile first-person controls.
 */
class HouseWalkView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile
    private var pendingMesh: MeshData? = null

    @Volatile
    private var pendingSpawn: Vec2? = null

    @Volatile
    private var pendingDesign: HouseRenderProfile? = null

    @Volatile
    private var movementForward = 0f

    @Volatile
    private var movementStrafe = 0f

    private var collisionWorld: CollisionWorld? = null
    private var walls: GlMesh? = null
    private var floor: GlMesh? = null
    private var trim: GlMesh? = null
    private var shaderProgram = 0
    private var uMvp = -1
    private var uColor = -1

    private var wallColor = floatArrayOf(0.94f, 0.92f, 0.87f)
    private var floorColor = floatArrayOf(0.73f, 0.68f, 0.59f)
    private var trimColor = floatArrayOf(0.34f, 0.24f, 0.17f)

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private var aspect = 1f
    private var cameraX = 0f
    private var cameraZ = 0f
    private var yaw = 0f
    private var pitch = 0f
    private var velocityX = 0f
    private var velocityZ = 0f
    private var lastFrameNanos = 0L
    private var walkPhase = 0f

    private var movementPointerId = INVALID_POINTER
    private var lookPointerId = INVALID_POINTER
    private var movementOriginX = 0f
    private var movementOriginY = 0f
    private var previousLookX = 0f
    private var previousLookY = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun setFloorPlan(plan: FloorPlan) {
        val world = CollisionWorld(plan)
        val design = ReferenceDrivenDesignEngine.synthesize(plan)
        collisionWorld = world
        pendingSpawn = world.findSpawn(PLAYER_RADIUS)
        pendingDesign = design
        pendingMesh = HouseMeshBuilder.build(
            plan = plan,
            wallHeightOverride = design.wallHeightMeters,
            doorHeightOverride = design.doorHeightMeters,
        )
    }

    /** Allows a Compose/custom overlay to drive the same analog movement pipeline if desired. */
    fun setMovementInput(forward: Float, strafe: Float) {
        movementForward = forward.coerceIn(-1f, 1f)
        movementStrafe = strafe.coerceIn(-1f, 1f)
    }

    fun stopMovement() {
        movementForward = 0f
        movementStrafe = 0f
    }

    /** Compatibility controls used by the milestone-1 arrow buttons. */
    fun moveForward(amountMeters: Float) {
        queueEvent { moveImmediate(localForwardMeters = amountMeters, localStrafeMeters = 0f) }
    }

    fun strafe(amountMeters: Float) {
        queueEvent { moveImmediate(localForwardMeters = 0f, localStrafeMeters = amountMeters) }
    }

    fun resetCamera() {
        val spawn = collisionWorld?.findSpawn(PLAYER_RADIUS) ?: Vec2(0f, 0f)
        stopMovement()
        queueEvent {
            cameraX = spawn.x
            cameraZ = spawn.z
            yaw = 0f
            pitch = 0f
            velocityX = 0f
            velocityZ = 0f
            walkPhase = 0f
            lastFrameNanos = 0L
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.90f, 0.92f, 0.94f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(shaderProgram, "uMvp")
        uColor = GLES30.glGetUniformLocation(shaderProgram, "uColor")
        lastFrameNanos = 0L
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
            trim?.destroy()
            walls = uploadMesh(mesh.wallVertices, mesh.wallIndices)
            floor = uploadMesh(mesh.floorVertices, mesh.floorIndices)
            trim = uploadMesh(mesh.trimVertices, mesh.trimIndices)
        }
        pendingDesign?.let { design ->
            pendingDesign = null
            wallColor = floatArrayOf(
                design.palette.wall.r,
                design.palette.wall.g,
                design.palette.wall.b,
            )
            floorColor = floatArrayOf(
                design.palette.floor.r,
                design.palette.floor.g,
                design.palette.floor.b,
            )
            trimColor = floatArrayOf(
                design.palette.wood.r,
                design.palette.wood.g,
                design.palette.wood.b,
            )
        }
        pendingSpawn?.let { spawn ->
            pendingSpawn = null
            cameraX = spawn.x
            cameraZ = spawn.z
            velocityX = 0f
            velocityZ = 0f
            lastFrameNanos = 0L
        }

        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            0f
        } else {
            ((now - lastFrameNanos) / NANOS_PER_SECOND).toFloat().coerceIn(0f, MAX_FRAME_STEP_SECONDS)
        }
        lastFrameNanos = now
        updateMovement(deltaSeconds)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (shaderProgram == 0) return

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, 0.045f, 100f)

        val horizontalSpeed = sqrt(velocityX * velocityX + velocityZ * velocityZ)
        val bobStrength = (horizontalSpeed / WALK_SPEED_METERS_PER_SECOND).coerceIn(0f, 1f)
        val eyeY = EYE_HEIGHT_METERS + sin(walkPhase) * HEAD_BOB_METERS * bobStrength
        val cosPitch = cos(pitch)
        val lookX = cameraX + sin(yaw) * cosPitch
        val lookY = eyeY + sin(pitch)
        val lookZ = cameraZ - cos(yaw) * cosPitch
        Matrix.setLookAtM(
            view,
            0,
            cameraX,
            eyeY,
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
            GLES30.glUniform4f(uColor, floorColor[0], floorColor[1], floorColor[2], 1f)
            drawMesh(it)
        }
        walls?.let {
            GLES30.glUniform4f(uColor, wallColor[0], wallColor[1], wallColor[2], 1f)
            drawMesh(it)
        }
        trim?.let {
            GLES30.glUniform4f(uColor, trimColor[0], trimColor[1], trimColor[2], 1f)
            drawMesh(it)
        }
    }

    private fun updateMovement(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return

        var forward = movementForward
        var strafe = movementStrafe
        val rawMagnitude = sqrt(forward * forward + strafe * strafe)
        if (rawMagnitude > 1f) {
            forward /= rawMagnitude
            strafe /= rawMagnitude
        }

        val inputMagnitude = min(1f, rawMagnitude)
        val speedMultiplier = if (inputMagnitude > FAST_WALK_THRESHOLD) FAST_WALK_MULTIPLIER else 1f
        val targetSpeed = WALK_SPEED_METERS_PER_SECOND * inputMagnitude * speedMultiplier

        val forwardX = sin(yaw)
        val forwardZ = -cos(yaw)
        val rightX = cos(yaw)
        val rightZ = sin(yaw)
        val desiredX = (forwardX * forward + rightX * strafe) * targetSpeed
        val desiredZ = (forwardZ * forward + rightZ * strafe) * targetSpeed

        // Exponential response is frame-rate independent and feels less robotic than instant speed.
        val response = 1f - exp((-MOVEMENT_RESPONSE * deltaSeconds).toDouble()).toFloat()
        velocityX += (desiredX - velocityX) * response
        velocityZ += (desiredZ - velocityZ) * response

        if (inputMagnitude < INPUT_EPSILON && abs(velocityX) < VELOCITY_EPSILON && abs(velocityZ) < VELOCITY_EPSILON) {
            velocityX = 0f
            velocityZ = 0f
            return
        }

        val intendedX = cameraX + velocityX * deltaSeconds
        val intendedZ = cameraZ + velocityZ * deltaSeconds
        val moved = collisionWorld?.move(
            position = Vec2(cameraX, cameraZ),
            deltaX = velocityX * deltaSeconds,
            deltaZ = velocityZ * deltaSeconds,
            radius = PLAYER_RADIUS,
        ) ?: Vec2(intendedX, intendedZ)

        // Kill only the blocked component so the player naturally slides along walls/corners.
        if (abs(moved.x - intendedX) > COLLISION_VELOCITY_TOLERANCE) velocityX *= COLLISION_DAMPING
        if (abs(moved.z - intendedZ) > COLLISION_VELOCITY_TOLERANCE) velocityZ *= COLLISION_DAMPING
        cameraX = moved.x
        cameraZ = moved.z

        val speed = sqrt(velocityX * velocityX + velocityZ * velocityZ)
        if (speed > VELOCITY_EPSILON) {
            walkPhase += deltaSeconds * (HEAD_BOB_BASE_FREQUENCY + speed * HEAD_BOB_SPEED_FACTOR)
        }
    }

    private fun moveImmediate(localForwardMeters: Float, localStrafeMeters: Float) {
        val dx = sin(yaw) * localForwardMeters + cos(yaw) * localStrafeMeters
        val dz = -cos(yaw) * localForwardMeters + sin(yaw) * localStrafeMeters
        val moved = collisionWorld?.move(
            position = Vec2(cameraX, cameraZ),
            deltaX = dx,
            deltaZ = dz,
            radius = PLAYER_RADIUS,
        ) ?: Vec2(cameraX + dx, cameraZ + dz)
        cameraX = moved.x
        cameraZ = moved.z
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                handlePointerDown(event, event.actionIndex)

            MotionEvent.ACTION_MOVE -> handlePointerMove(event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                handlePointerUp(event.getPointerId(event.actionIndex))

            MotionEvent.ACTION_CANCEL -> releaseAllPointers()
        }
        return true
    }

    private fun handlePointerDown(event: MotionEvent, index: Int) {
        val pointerId = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        val movementZone = x <= width * MOVEMENT_ZONE_FRACTION

        if (movementZone && movementPointerId == INVALID_POINTER) {
            movementPointerId = pointerId
            movementOriginX = x
            movementOriginY = y
            setMovementInput(0f, 0f)
        } else if (lookPointerId == INVALID_POINTER) {
            lookPointerId = pointerId
            previousLookX = x
            previousLookY = y
        } else if (movementPointerId == INVALID_POINTER) {
            movementPointerId = pointerId
            movementOriginX = x
            movementOriginY = y
            setMovementInput(0f, 0f)
        }
    }

    private fun handlePointerMove(event: MotionEvent) {
        if (movementPointerId != INVALID_POINTER) {
            val index = event.findPointerIndex(movementPointerId)
            if (index >= 0) {
                updateAnalogMovement(event.getX(index), event.getY(index))
            }
        }

        if (lookPointerId != INVALID_POINTER) {
            val index = event.findPointerIndex(lookPointerId)
            if (index >= 0) {
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - previousLookX
                val dy = y - previousLookY
                previousLookX = x
                previousLookY = y
                queueEvent {
                    yaw -= dx * LOOK_SENSITIVITY
                    pitch = (pitch - dy * LOOK_SENSITIVITY).coerceIn(-MAX_PITCH, MAX_PITCH)
                }
            }
        }
    }

    private fun updateAnalogMovement(x: Float, y: Float) {
        val radius = (min(width, height) * JOYSTICK_RADIUS_FRACTION).coerceAtLeast(MIN_JOYSTICK_RADIUS_PX)
        val dx = x - movementOriginX
        val dy = y - movementOriginY
        val magnitudePixels = sqrt(dx * dx + dy * dy)
        if (magnitudePixels <= radius * JOYSTICK_DEAD_ZONE) {
            setMovementInput(0f, 0f)
            return
        }

        val normalizedMagnitude = (magnitudePixels / radius).coerceAtMost(1f)
        val scaledMagnitude = ((normalizedMagnitude - JOYSTICK_DEAD_ZONE) / (1f - JOYSTICK_DEAD_ZONE))
            .coerceIn(0f, 1f)
        val directionX = if (magnitudePixels > 0f) dx / magnitudePixels else 0f
        val directionY = if (magnitudePixels > 0f) dy / magnitudePixels else 0f
        setMovementInput(
            forward = -directionY * scaledMagnitude,
            strafe = directionX * scaledMagnitude,
        )
    }

    private fun handlePointerUp(pointerId: Int) {
        if (pointerId == movementPointerId) {
            movementPointerId = INVALID_POINTER
            stopMovement()
        }
        if (pointerId == lookPointerId) {
            lookPointerId = INVALID_POINTER
        }
    }

    private fun releaseAllPointers() {
        movementPointerId = INVALID_POINTER
        lookPointerId = INVALID_POINTER
        stopMovement()
    }

    override fun onDetachedFromWindow() {
        stopMovement()
        queueEvent {
            walls?.destroy()
            floor?.destroy()
            trim?.destroy()
            walls = null
            floor = null
            trim = null
            if (shaderProgram != 0) {
                GLES30.glDeleteProgram(shaderProgram)
                shaderProgram = 0
            }
        }
        super.onDetachedFromWindow()
    }

    private fun uploadMesh(vertices: FloatArray, indices: IntArray): GlMesh? {
        if (vertices.isEmpty() || indices.isEmpty()) return null

        val vertexBuffer = allocateFloatBuffer(vertices.size)
        vertexBuffer.put(vertices).position(0)
        val indexBuffer = allocateIntBuffer(indices.size)
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
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(
            1,
            3,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            3 * Float.SIZE_BYTES,
        )
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_INT, 0)

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
        private const val INVALID_POINTER = -1
        private const val EYE_HEIGHT_METERS = 1.65f
        private const val PLAYER_RADIUS = CollisionWorld.DEFAULT_PLAYER_RADIUS
        private const val WALK_SPEED_METERS_PER_SECOND = 1.72f
        private const val FAST_WALK_THRESHOLD = 0.93f
        private const val FAST_WALK_MULTIPLIER = 1.16f
        private const val MOVEMENT_RESPONSE = 11.5f
        private const val FIELD_OF_VIEW_DEGREES = 70f
        private const val LOOK_SENSITIVITY = 0.0036f
        private const val MOVEMENT_ZONE_FRACTION = 0.47f
        private const val JOYSTICK_RADIUS_FRACTION = 0.13f
        private const val MIN_JOYSTICK_RADIUS_PX = 78f
        private const val JOYSTICK_DEAD_ZONE = 0.10f
        private const val HEAD_BOB_METERS = 0.016f
        private const val HEAD_BOB_BASE_FREQUENCY = 4.0f
        private const val HEAD_BOB_SPEED_FACTOR = 3.2f
        private const val INPUT_EPSILON = 0.01f
        private const val VELOCITY_EPSILON = 0.008f
        private const val COLLISION_VELOCITY_TOLERANCE = 0.004f
        private const val COLLISION_DAMPING = 0.12f
        private const val MAX_FRAME_STEP_SECONDS = 0.05f
        private const val NANOS_PER_SECOND = 1_000_000_000.0
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

private fun allocateFloatBuffer(size: Int): FloatBuffer =
    ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

private fun allocateIntBuffer(size: Int): IntBuffer =
    ByteBuffer.allocateDirect(size * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
