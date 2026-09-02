package com.manzzzl.ai.screens

import android.content.Context
import java.io.File
import java.net.URL

object ModelRepository {
    suspend fun getModelFile(context: Context, modelUrl: String): File {
        val cacheFile = File(context.cacheDir, "house.glb")

        if (cacheFile.exists()) {
            return cacheFile
        }

        URL(modelUrl).openStream().use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return cacheFile
    }
}
