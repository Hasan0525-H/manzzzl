package com.manzl.app.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import com.manzl.app.analysis.StairLevelLinker
import com.manzl.app.design.HouseRenderProfile
import com.manzl.app.model.BuildingPlan
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
 * Rendering, movement, level-aware collision, animated doors and visual-profile synthesis all run
 * locally on the phone. The left side of the surface is a dynamic analog stick (touch and drag);
 * the right side controls free-look. Two fingers can be used simultaneously, matching modern mobile
 * first-person controls.
 *
 * Exterior finish is deliberately uploaded as a separate GPU mesh. The canonical wall batch keeps
 * the interior plaster material, while the side-aware facade batch receives the Saudi stone/plaster
 * material without changing wall topology, collision or opening geometry.
 */
class HouseWalkView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile
    private var pendingMesh: MeshData? = null

    @Volatile
    private var pendingFacadeMesh: FacadeMesh? = null

    @Volatile
    private var pendingSpawn: BuildingSpawn? = null

    @Volatile
    private var pendingDesign: HouseRenderProfile? = null

    @Volatile
    private var pendingDoorMeshRefresh = false

    @Volatile
    private var movementForward = 0f

    @Volatile
    private var movementStrafe = 0f

    private var walkWorld: MultiLevelWalkWorld? = null

    @Volatile
    private var doorWorld: InteractiveDoorWorld? = null

    private var currentLevelId = ""
    private var walls: GlMesh? = null
    private var facade: GlMesh? = null
    private var floor: GlMesh? = null
    private var ceiling: GlMesh? = null
    private var trim: GlMesh? = null
    private var glass: GlMesh? = null
    private var doorLeaves: GlMesh? = null
    private var shaderProgram = 0
    private var uMvp = -1
    private var uColor = -1
    private var uCameraPosition = -1
    private var uMaterial = -1

    private var wallColor = floatArrayOf(0.94f, 0.92f, 0.87f)
    private var facadeColor = floatArrayOf(0.67f, 0.58f, 0.46f)
    private var floorColor = floatArrayOf(0.73f, 0.68f, 0.59f)
    private var ceilingColor = floatArrayOf(0.98f, 0.975f, 0.96f)
    private var trimColor = floatArrayOf(0.34f, 0.24f, 0.17f)
    private var glassColor = floatArrayOf(0.68f, 0.78f, 0.80f)

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private var aspect = 1f
    private var cameraX = 0f
    private var cameraZ = 0f
    /** Global floor elevation, not eye height. */
    private var cameraElevationY = 0f
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

    /** Backward-compatible one-floor entry point; it now uses the same multi-level runtime. */
    fun setFloorPlan(plan: FloorPlan) {
        setBuildingPlan(BuildingPlan.singleLevel(plan))
    }

    /**
     * Loads a complete building without changing any measured floor geometry.
     *
     * When callers provide several floors without stair links, links are inferred conservatively
     * from already-detected stair evidence. The renderer stacks each floor at its declared elevation,
     * MultiLevelWalkWorld owns wall/stair collision, and InteractiveDoorWorld owns the exact moving
     * leaf pose plus synchronized door collision.
     */
    fun setBuildingPlan(building: BuildingPlan) {
        if (building.levels.isEmpty()) return

        val prepared = if (building.levels.size > 1 && building.stairLinks.isEmpty()) {
            StairLevelLinker.link(building)
        } else {
            building
        }
        val world = MultiLevelWalkWorld(prepared)
        val scene = WalkthroughSceneAssembler.build(prepared)

        walkWorld = world
        doorWorld = InteractiveDoorWorld(prepared)
        pendingDoorMeshRefresh = true
        pendingSpawn = world.findInitialSpawn(PLAYER_RADIUS)
        pendingDesign = scene.primaryDesign
        pendingMesh = scene.staticMesh
        pendingFacadeMesh = scene.facadeMesh
    }

    fun setMovementInput(forward: Float, strafe: Float) {
        movementForward = forward.coerceIn(-1f, 1f)
        movementStrafe = strafe.coerceIn(-1f, 1f)
    }

    fun stopMovement() {
        movementForward = 0f
        movementStrafe = 0f
    }

    fun moveForward(amountMeters: Float) {
        queueEvent { moveImmediate(localForwardMeters = amountMeters, localStrafeMeters = 0f) }
    }

    fun strafe(amountMeters: Float) {
        queueEvent { moveImmediate(localForwardMeters = 0f, localStrafeMeters = amountMeters) }
    }

    fun resetCamera() {
        val spawn = walkWorld?.findInitialSpawn(PLAYER_RADIUS)
            ?: BuildingSpawn("", Vec2(0f, 0f), 0f)
        stopMovement()
        queueEvent {
            currentLevelId = spawn.levelId
            cameraX = spawn.position.x
            cameraZ = spawn.position.z
            cameraElevationY = spawn.globalElevationMeters
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
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(shaderProgram, "uMvp")
        uColor = GLES30.glGetUniformLocation(shaderProgram, "uColor")
        uCameraPosition = GLES30.glGetUniformLocation(shaderProgram, "uCameraPosition")
        uMaterial = GLES30.glGetUniformLocation(shaderProgram, "uMaterial")
        lastFrameNanos = 0L
        pendingDoorMeshRefresh = true
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
            ceiling?.destroy()
            trim?.destroy()
            glass?.destroy()
            walls = uploadMesh(mesh.wallVertices, mesh.wallIndices)
            floor = uploadMesh(mesh.floorVertices, mesh.floorIndices)
            ceiling = uploadMesh(mesh.ceilingVertices, mesh.ceilingIndices)
            trim = uploadMesh(mesh.trimVertices, mesh.trimIndices)
            glass = uploadMesh(mesh.glassVertices, mesh.glassIndices)
        }
        pendingFacadeMesh?.let { mesh ->
            pendingFacadeMesh = null
            facade?.destroy()
            facade = uploadMesh(mesh.vertices, mesh.indices)
        }
        pendingDesign?.let { design ->
            pendingDesign = null
            wallColor = floatArrayOf(
                design.palette.wall.r,
                design.palette.wall.g,
                design.palette.wall.b,
            )
            facadeColor = floatArrayOf(
                design.palette.stone.r,
                design.palette.stone.g,
                design.palette.stone.b,
            )
            floorColor = floatArrayOf(
                design.palette.floor.r,
                design.palette.floor.g,
                design.palette.floor.b,
            )
            ceilingColor = floatArrayOf(
                design.palette.ceiling.r,
                design.palette.ceiling.g,
                design.palette.ceiling.b,
            )
            trimColor = floatArrayOf(
                design.palette.wood.r,
                design.palette.wood.g,
                design.palette.wood.b,
            )
            glassColor = floatArrayOf(
                (design.palette.ceiling.r * 0.72f).coerceIn(0f, 1f),
                (design.palette.ceiling.g * 0.82f).coerceIn(0f, 1f),
                (design.palette.ceiling.b * 0.88f).coerceIn(0f, 1f),
            )
        }
        pendingSpawn?.let { spawn ->
            pendingSpawn = null
            currentLevelId = spawn.levelId
            cameraX = spawn.position.x
            cameraZ = spawn.position.z
            cameraElevationY = spawn.globalElevationMeters
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

        val doorsChanged = doorWorld?.update(
            currentLevelId = currentLevelId,
            playerPosition = Vec2(cameraX, cameraZ),
            deltaSeconds = deltaSeconds,
            playerRadius = PLAYER_RADIUS,
        ) == true
        updateMovement(deltaSeconds)
        if (pendingDoorMeshRefresh || doorsChanged) {
            pendingDoorMeshRefresh = false
            refreshDoorLeafMesh()
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (shaderProgram == 0) return

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, 0.045f, 100f)

        val horizontalSpeed = sqrt(velocityX * velocityX + velocityZ * velocityZ)
        val bobStrength = (horizontalSpeed / WALK_SPEED_METERS_PER_SECOND).coerceIn(0f, 1f)
        val eyeY = cameraElevationY + EYE_HEIGHT_METERS + sin(walkPhase) * HEAD_BOB_METERS * bobStrength
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
        GLES30.glUniform3f(uCameraPosition, cameraX, eyeY, cameraZ)

        floor?.let {
            applyMaterial(floorColor, alpha = 1f, roughness = 0.48f, metallic = 0.02f, ambientOcclusion = 0.92f)
            drawMesh(it)
        }
        walls?.let {
            applyMaterial(wallColor, alpha = 1f, roughness = 0.82f, metallic = 0f, ambientOcclusion = 0.95f)
            drawMesh(it)
        }
        facade?.let {
            applyMaterial(
                facadeColor,
                alpha = 1f,
                roughness = FACADE_ROUGHNESS,
                metallic = 0f,
                ambientOcclusion = FACADE_AMBIENT_OCCLUSION,
            )
            drawMesh(it)
        }
        ceiling?.let {
            applyMaterial(ceilingColor, alpha = 1f, roughness = 0.92f, metallic = 0f, ambientOcclusion = 0.98f)
            drawMesh(it)
        }
        trim?.let {
            applyMaterial(trimColor, alpha = 1f, roughness = 0.36f, metallic = 0.01f, ambientOcclusion = 0.88f)
            drawMesh(it)
        }
        doorLeaves?.let {
            applyMaterial(trimColor, alpha = 1f, roughness = 0.42f, metallic = 0.01f, ambientOcclusion = 0.90f)
            drawMesh(it)
        }
        glass?.let {
            GLES30.glDepthMask(false)
            applyMaterial(glassColor, alpha = 0.34f, roughness = 0.10f, metallic = 0.06f, ambientOcclusion = 0.86f)
            drawMesh(it)
            GLES30.glDepthMask(true)
        }
    }

    private fun applyMaterial(
        color: FloatArray,
        alpha: Float,
        roughness: Float,
        metallic: Float,
        ambientOcclusion: Float,
    ) {
        GLES30.glUniform4f(uColor, color[0], color[1], color[2], alpha)
        GLES30.glUniform3f(
            uMaterial,
            roughness.coerceIn(0.06f, 1f),
            metallic.coerceIn(0f, 1f),
            ambientOcclusion.coerceIn(0f, 1f),
        )
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

        val response = 1f - exp((-MOVEMENT_RESPONSE * deltaSeconds).toDouble()).toFloat()
        velocityX += (desiredX - velocityX) * response
        velocityZ += (desiredZ - velocityZ) * response

        if (inputMagnitude < INPUT_EPSILON && abs(velocityX) < VELOCITY_EPSILON && abs(velocityZ) < VELOCITY_EPSILON) {
            velocityX = 0f
            velocityZ = 0f
            return
        }

        val moveX = velocityX * deltaSeconds
        val moveZ = velocityZ * deltaSeconds
        val intendedX = cameraX + moveX
        val intendedZ = cameraZ + moveZ
        val from = Vec2(cameraX, cameraZ)
        val result = walkWorld?.move(
            levelId = currentLevelId,
            position = from,
            globalElevationMeters = cameraElevationY,
            deltaX = moveX,
            deltaZ = moveZ,
            radius = PLAYER_RADIUS,
        )
        val wallResolved = result?.position ?: Vec2(intendedX, intendedZ)
        val resultingLevelId = result?.levelId ?: currentLevelId
        val moved = doorWorld?.resolveMove(
            levelId = resultingLevelId,
            from = from,
            to = wallResolved,
            radius = PLAYER_RADIUS,
        ) ?: wallResolved

        if (abs(moved.x - intendedX) > COLLISION_VELOCITY_TOLERANCE) velocityX *= COLLISION_DAMPING
        if (abs(moved.z - intendedZ) > COLLISION_VELOCITY_TOLERANCE) velocityZ *= COLLISION_DAMPING
        if (result?.levelTransition != null) {
            velocityX *= LEVEL_TRANSITION_VELOCITY_RETAIN
            velocityZ *= LEVEL_TRANSITION_VELOCITY_RETAIN
        }

        cameraX = moved.x
        cameraZ = moved.z
        if (result != null) {
            currentLevelId = result.levelId
            cameraElevationY = result.globalElevationMeters
        }

        val speed = sqrt(velocityX * velocityX + velocityZ * velocityZ)
        if (speed > VELOCITY_EPSILON) {
            walkPhase += deltaSeconds * (HEAD_BOB_BASE_FREQUENCY + speed * HEAD_BOB_SPEED_FACTOR)
        }
    }

    private fun moveImmediate(localForwardMeters: Float, localStrafeMeters: Float) {
        val dx = sin(yaw) * localForwardMeters + cos(yaw) * localStrafeMeters
        val dz = -cos(yaw) * localForwardMeters + sin(yaw) * localStrafeMeters
        val from = Vec2(cameraX, cameraZ)
        val result = walkWorld?.move(
            levelId = currentLevelId,
            position = from,
            globalElevationMeters = cameraElevationY,
            deltaX = dx,
            deltaZ = dz,
            radius = PLAYER_RADIUS,
        )
        val wallResolved = result?.position ?: Vec2(cameraX + dx, cameraZ + dz)
        val resultingLevelId = result?.levelId ?: currentLevelId
        val moved = doorWorld?.resolveMove(
            levelId = resultingLevelId,
            from = from,
            to = wallResolved,
            radius = PLAYER_RADIUS,
        ) ?: wallResolved
        cameraX = moved.x
        cameraZ = moved.z
        if (result != null) {
            currentLevelId = result.levelId
            cameraElevationY = result.globalElevationMeters
        }
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
            facade?.destroy()
            floor?.destroy()
            ceiling?.destroy()
            trim?.destroy()
            glass?.destroy()
            doorLeaves?.destroy()
            walls = null
            facade = null
            floor = null
            ceiling = null
            trim = null
            glass = null
            doorLeaves = null
            if (shaderProgram != 0) {
                GLES30.glDeleteProgram(shaderProgram)
                shaderProgram = 0
            }
        }
        super.onDetachedFromWindow()
    }

    /**
     * Updates the animated door mesh without reallocating GPU buffers while the number of doors is
     * unchanged. This keeps opening/closing animation cheap on mid-range phones.
     */
    private fun refreshDoorLeafMesh() {
        val dynamic = DoorLeafMeshBuilder.build(doorWorld?.poses().orEmpty())
        if (dynamic.vertices.isEmpty() || dynamic.indices.isEmpty()) {
            doorLeaves?.destroy()
            doorLeaves = null
            return
        }

        val existing = doorLeaves
        if (
            existing != null &&
            existing.vertexFloatCount == dynamic.vertices.size &&
            existing.indexCount == dynamic.indices.size
        ) {
            val vertexBuffer = allocateFloatBuffer(dynamic.vertices.size)
            vertexBuffer.put(dynamic.vertices).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, existing.vbo)
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER,
                0,
                dynamic.vertices.size * Float.SIZE_BYTES,
                vertexBuffer,
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            return
        }

        existing?.destroy()
        doorLeaves = uploadMesh(
            vertices = dynamic.vertices,
            indices = dynamic.indices,
            usage = GLES30.GL_DYNAMIC_DRAW,
        )
    }

    private fun uploadMesh(
        vertices: FloatArray,
        indices: IntArray,
        usage: Int = GLES30.GL_STATIC_DRAW,
    ): GlMesh? {
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
            usage,
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * Int.SIZE_BYTES,
            indexBuffer,
            usage,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        return GlMesh(
            vbo = vbo,
            ibo = ibo,
            indexCount = indices.size,
            vertexFloatCount = vertices.size,
        )
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
        val vertexFloatCount: Int,
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
        private const val LEVEL_TRANSITION_VELOCITY_RETAIN = 0.72f
        private const val MAX_FRAME_STEP_SECONDS = 0.05f
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val FACADE_ROUGHNESS = 0.76f
        private const val FACADE_AMBIENT_OCCLUSION = 0.93f
        private val MAX_PITCH = (80.0 * PI / 180.0).toFloat()
        private const val STRIDE_BYTES = 6 * Float.SIZE_BYTES

        private const val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;

            uniform mat4 uMvp;
            out vec3 vNormal;
            out vec3 vWorldPosition;

            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vNormal = aNormal;
                vWorldPosition = aPosition;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            uniform vec4 uColor;
            uniform vec3 uCameraPosition;
            uniform vec3 uMaterial;
            in vec3 vNormal;
            in vec3 vWorldPosition;
            out vec4 outColor;

            const float PI = 3.14159265359;

            float distributionGGX(vec3 n, vec3 h, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float nDotH = max(dot(n, h), 0.0);
                float nDotH2 = nDotH * nDotH;
                float denominator = nDotH2 * (a2 - 1.0) + 1.0;
                return a2 / max(PI * denominator * denominator, 0.0001);
            }

            float geometrySchlickGGX(float nDotV, float roughness) {
                float r = roughness + 1.0;
                float k = (r * r) / 8.0;
                return nDotV / max(nDotV * (1.0 - k) + k, 0.0001);
            }

            float geometrySmith(vec3 n, vec3 v, vec3 l, float roughness) {
                float nDotV = max(dot(n, v), 0.0);
                float nDotL = max(dot(n, l), 0.0);
                return geometrySchlickGGX(nDotV, roughness) * geometrySchlickGGX(nDotL, roughness);
            }

            vec3 fresnelSchlick(float cosTheta, vec3 f0) {
                return f0 + (1.0 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
            }

            void main() {
                vec3 baseColor = max(uColor.rgb, vec3(0.001));
                float roughness = clamp(uMaterial.x, 0.06, 1.0);
                float metallic = clamp(uMaterial.y, 0.0, 1.0);
                float ao = clamp(uMaterial.z, 0.0, 1.0);

                vec3 n = normalize(vNormal);
                vec3 v = normalize(uCameraPosition - vWorldPosition);
                vec3 l = normalize(vec3(-0.34, 0.88, 0.31));
                vec3 h = normalize(v + l);
                float nDotL = max(dot(n, l), 0.0);
                float nDotV = max(dot(n, v), 0.001);

                vec3 f0 = mix(vec3(0.04), baseColor, metallic);
                vec3 f = fresnelSchlick(max(dot(h, v), 0.0), f0);
                float ndf = distributionGGX(n, h, roughness);
                float geometry = geometrySmith(n, v, l, roughness);
                vec3 specular = (ndf * geometry * f) / max(4.0 * nDotV * nDotL, 0.001);

                vec3 kS = f;
                vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
                vec3 warmDaylight = vec3(1.18, 1.10, 0.99);
                vec3 direct = (kD * baseColor / PI + specular) * warmDaylight * nDotL;

                float upward = max(n.y, 0.0);
                float downward = max(-n.y, 0.0);
                float vertical = 1.0 - abs(n.y);
                vec3 ambient = baseColor * ao * (0.24 + upward * 0.12 + downward * 0.17 + vertical * 0.08);
                float heightBounce = clamp(vWorldPosition.y / 3.2, 0.0, 1.0);
                vec3 bounced = baseColor * (heightBounce * 0.055 + downward * 0.07);

                vec3 hdr = ambient + direct + bounced;
                vec3 mapped = vec3(1.0) - exp(-hdr * 1.18);
                vec3 gammaCorrected = pow(max(mapped, vec3(0.0)), vec3(1.0 / 2.2));
                outColor = vec4(gammaCorrected, uColor.a);
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
