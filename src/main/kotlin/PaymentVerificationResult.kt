package com

import kotlinx.serialization.Serializable

@Serializable
data class PaymentVerificationResult(
    val verified: Boolean,
    val message: String? = null
)
