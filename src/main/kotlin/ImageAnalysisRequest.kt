package com

import kotlinx.serialization.Serializable

@Serializable
data class ImageAnalysisRequest(
    val imageBase64: String,
    val prompt: String? = null
)
