package com.lrec.operator

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class LrecGLSurface(context: Context) : GLSurfaceView(context) {

    private val renderer: LrecRenderer

    init {

        setEGLContextClientVersion(2)

        renderer = LrecRenderer()

        setRenderer(renderer)

        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateFrame(bitmap: Bitmap) {

        renderer.updateBitmap(bitmap)

        requestRender()
    }

    private class LrecRenderer : Renderer {

        private var bitmap: Bitmap? = null
        private var textureId = IntArray(1)

        fun updateBitmap(bmp: Bitmap) {
            bitmap = bmp
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {

            gl?.glGenTextures(1, textureId, 0)

            gl?.glBindTexture(GL10.GL_TEXTURE_2D, textureId[0])

            gl?.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_LINEAR.toFloat()
            )

            gl?.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_LINEAR.toFloat()
            )
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {

            gl?.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {

            val bmp = bitmap ?: return

            GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bmp, 0)
        }
    }
}
