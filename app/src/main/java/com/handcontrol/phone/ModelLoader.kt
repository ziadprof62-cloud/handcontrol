package com.handcontrol.phone

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads gesture_recognizer.task. Order:
 *  1) app/src/main/assets/gesture_recognizer.task (if you put it there)
 *  2) a copy downloaded earlier into the app's private storage
 *  3) downloads it once from Google (needs internet the first time)
 * Must be called from a background thread.
 */
object ModelLoader {

    private const val FILE_NAME = "gesture_recognizer.task"

    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/" +
            "gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"

    fun load(context: Context): ByteBuffer {

        val bytes: ByteArray =
            try {
                context.assets.open(FILE_NAME).use { it.readBytes() }
            } catch (_: IOException) {
                fromStorage(context)
            }

        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()

        return buffer
    }

    private fun fromStorage(context: Context): ByteArray {

        val file = File(context.filesDir, FILE_NAME)

        if (!file.exists() || file.length() == 0L) {

            val tmp = File(context.filesDir, "$FILE_NAME.part")

            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            try {
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                conn.disconnect()
            }

            if (!tmp.renameTo(file)) {
                throw IOException("Could not save the model file")
            }
        }

        return file.readBytes()
    }
}
