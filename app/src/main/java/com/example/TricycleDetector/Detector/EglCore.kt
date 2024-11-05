package com.example.TricycleDetector.Detector

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class EglCore(
    sharedContext: EGLContext?,
    flags: Int
) {
    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    var eglConfig: EGLConfig? = null

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL14")
        }

        // Configure EGL
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, sharedContext ?: EGL14.EGL_NO_CONTEXT,
            contextAttribs, 0
        )
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    companion object {
        const val FLAG_RECORDABLE = 0x01
    }
}

class WindowSurface(
    private val eglCore: EglCore,
    private val surface: Surface,
    private val releaseSurface: Boolean
) {
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        createWindowSurface()
    }

    private fun createWindowSurface() {
        eglSurface = EGL14.eglCreateWindowSurface(
            eglCore.eglDisplay,
            eglCore.eglConfig,
            surface,
            null, 0
        )
    }

    fun release() {
        EGL14.eglDestroySurface(eglCore.eglDisplay, eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
        if (releaseSurface) {
            surface.release()
        }
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglCore.eglDisplay, eglSurface, eglSurface, eglCore.eglContext)
    }

    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(eglCore.eglDisplay, eglSurface)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglCore.eglDisplay, eglSurface, nsecs)
    }
}

class TextureProgram {
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord;
        }
    """

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    private var program: Int = 0
    private var textureID: Int = 0

    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,  // bottom left
        1.0f, -1.0f,  // bottom right
        -1.0f,  1.0f,  // top left
        1.0f,  1.0f   // top right
    )

    private val textureCoords = floatArrayOf(
        0.0f, 1.0f,    // bottom left
        1.0f, 1.0f,    // bottom right
        0.0f, 0.0f,    // top left
        1.0f, 0.0f     // top right
    )

    private var vertexBuffer: FloatBuffer
    private var textureBuffer: FloatBuffer

    init {
        // Initialize vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexCoords)
        vertexBuffer.position(0)

        // Initialize texture buffer
        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(textureCoords)
        textureBuffer.position(0)

        // Create program
        program = createProgram()

        // Generate texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureID = textures[0]

        // Set texture parameters
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureID)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun createProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: $info")
        }

        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader: $info")
        }

        return shader
    }

    fun drawFrame(bitmap: Bitmap, transform: FloatArray?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Set texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureID)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        // Get handle to vertex shader's vPosition member
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Get handle to texture coordinates location
        val textureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        GLES20.glEnableVertexAttribArray(textureCoordHandle)
        GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        // Get handle to shape's transformation matrix
        transform?.let {
            val transformHandle = GLES20.glGetUniformLocation(program, "uTransform")
            GLES20.glUniformMatrix4fv(transformHandle, 1, false, transform, 0)
        }

        // Draw the triangles
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordHandle)
    }

    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(textureID), 0)
        GLES20.glDeleteProgram(program)
    }
}