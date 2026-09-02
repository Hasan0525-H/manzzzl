package com.manzzzl.ai.screens

import android.content.Context
import java.io.File
import java.net.URL
import java.net.URLConnection

object ModelRepository {
    private const val CONNECT_TIMEOUT = 30000 // 30 seconds
    private const val READ_TIMEOUT = 30000    // 30 seconds

    suspend fun getModelFile(context: Context, modelUrl: String): File {
        val cacheFile = File(context.cacheDir, "house.glb")

        // Return cached file if it exists and has reasonable size
        if (cacheFile.exists() && cacheFile.length() > 1000) {
            return cacheFile
        }

        try {
            val url = URL(modelUrl)
            val connection: URLConnection = url.openConnection().apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }

            connection.inputStream.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Verify the file was written successfully
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                throw IllegalStateException("Failed to download model: file is empty")
            }

            return cacheFile
        } catch (e: Exception) {
            // Clean up failed download
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            throw RuntimeException("Failed to load model from $modelUrl: ${e.message}", e)
        }
    }
}
