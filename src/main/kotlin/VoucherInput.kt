package com

import kotlinx.serialization.Serializable

@Serializable
data class VoucherInput(
    val imageBase64: String
)
