package com

import kotlinx.serialization.Serializable

@Serializable
data class PaymentVerificationRequest(
    val fecha: String,
    val numeroTransaccion: String
)
