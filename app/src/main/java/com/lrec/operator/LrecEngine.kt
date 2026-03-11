package com.lrec.operator

object LrecEngine {

    init {
        System.loadLibrary("lrec_engine")
    }

    external fun open(path: String): Long

    external fun close(handle: Long)

    external fun getWidth(handle: Long): Int

    external fun getHeight(handle: Long): Int

    external fun getTotalFrames(handle: Long): Int

    external fun getDuration(handle: Long): Long

    external fun decodeFrame565(
        handle: Long,
        frame: Int,
        buffer: ShortArray?
    ): Boolean
}
