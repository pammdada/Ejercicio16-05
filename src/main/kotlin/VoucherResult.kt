package com

import kotlinx.serialization.Serializable

@Serializable
data class VoucherResult(
    val isVoucher: Boolean,
    val confidence: Float? = null,
    val data: VoucherData? = null,
    val message: String? = null
)

@Serializable
data class VoucherData(
    val descripcion: String? = null,
    val monto: String? = null,
    val numeroTransaccion: String? = null,
    val moneda: String? = null,

    //hora
    val fecha: String? = null,
    val hora: String? = null,
    val metodoPago: String? = null,
    val entidad: String? = null,
    val datosPersonales: DatosPersonales? = null
)

@Serializable
data class DatosPersonales(
    val nombre: String? = null,
    val dni: String? = null,
    val ruc: String? = null,
    val email: String? = null,
    val razonSocial: String? = null
)
