package com

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.ktor.*
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

private data class ClassificationResult(
    val isVoucher: Boolean,
    val confidence: Float,
    val originalInput: VoucherInput
)

private val json = Json { ignoreUnknownKeys = true }

private fun voucherStrategy(): AIAgentGraphStrategy<VoucherInput, VoucherResult> {
    return strategy("extract-voucher") {

        val classifyNode by node<VoucherInput, ClassificationResult>("classify") { input ->
            val base64Payload = input.imageBase64.substringAfter(",")
            llm.writeSession {
                appendPrompt {
                    user {
                        text("¿Es una iamgen? Responde ÚNICAMENTE con un JSON en este formato exacto: {\"isVoucher\": true/false, \"confidence\": 0.0-1.0}")
                        image(
                            AttachmentSource.Image(
                                content = AttachmentContent.Binary.Base64(base64Payload),
                                format = "png",
                                mimeType = "image/png"
                            )
                        )
                    }
                }
                val response = requestLLMWithoutTools()
                try {
                    val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                    val cleanJson = text.substringAfter("```json").substringAfter("```").substringBefore("```").trim().ifBlank { text.trim() }
                    val obj = Json.parseToJsonElement(cleanJson).jsonObject
                    val isV = obj["isVoucher"]?.jsonPrimitive?.boolean ?: false
                    val conf = obj["confidence"]?.jsonPrimitive?.float ?: 0.0f
                    ClassificationResult(isV, conf, input)
                } catch (_: Exception) {
                    ClassificationResult(false, 0.0f, input)
                }
            }
        }

        val extractNode by node<ClassificationResult, VoucherData>("extract") { result ->
            if (!result.isVoucher) error("Not a voucher")
            val base64Payload = result.originalInput.imageBase64.substringAfter(",")
            llm.writeSession {
                appendPrompt {
                    user {
                        text("""
                            Extrae los siguientes datos del comprobante y responde ÚNICAMENTE en JSON, sin marcas de markdown ni bloques de código, solo JSON puro:
                            {
                              "descripcion": "Descripcion breve del tipo de comprobante (max. 6 palabras, ej: Boleta de venta de combustible)",
                              "monto": "Monto total del comprobante",
                              "numeroTransaccion": "Numero de transaccion o comprobante",
                              "moneda": "Moneda (PEN, USD, etc.)",
                              "fecha": "Fecha del comprobante",
                              "metodoPago": "Metodo de pago",
                              "datosPersonales": {
                                "nombre": "Nombre completo del cliente o consumidor",
                                "dni": "DNI del cliente (si aparece)",
                                "ruc": "RUC del cliente (si aparece, generalmente en facturas)",
                                "email": "Correo electronico (si aparece)",
                                "razonSocial": "Razon social (si aparece, generalmente en facturas)"
                              }
                            }
                        """.trimIndent())
                        image(
                            AttachmentSource.Image(
                                content = AttachmentContent.Binary.Base64(base64Payload),
                                format = "png",
                                mimeType = "image/png"
                            )
                        )
                    }
                }
                val response = requestLLMWithoutTools()
                val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                val cleanJson = text.substringAfter("```json").substringAfter("```").substringBefore("```").trim().ifBlank { text.trim() }
                try {
                    json.decodeFromString<VoucherData>(cleanJson)
                } catch (_: Exception) {
                    VoucherData()
                }
            }
        }

        val buildResultSuccess by node<VoucherData, VoucherResult>("buildSuccess") { data ->
            VoucherResult(isVoucher = true, data = data)
        }

        val buildResultFail by node<ClassificationResult, VoucherResult>("buildFail") { result ->
            VoucherResult(
                isVoucher = false,
                confidence = result.confidence,
                message = "La imagen no corresponde a un comprobante de pago, boleta o factura."
            )
        }

        edge(nodeStart forwardTo classifyNode)
        edge(classifyNode forwardTo extractNode onCondition { it.isVoucher })
        edge(classifyNode forwardTo buildResultFail onCondition { !it.isVoucher })
        edge(extractNode forwardTo buildResultSuccess)
        edge(buildResultFail forwardTo nodeFinish)
        edge(buildResultSuccess forwardTo nodeFinish)
    }
}

fun Application.configureRouting() {
    install(ContentNegotiation) {
        json()
    }

    install(Koog)

    routing {
        get("/hola") {
            call.respondText("Funciona")
        }

        route("/ai") {

            post("/analyze-image") {
                val request = call.receive<ImageAnalysisRequest>()

                val base64Payload = request.imageBase64.substringAfter(",")
                if (base64Payload.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Empty base64 data"))
                    return@post
                }

                val userPrompt = request.prompt ?: "Describe esta imagen en detalle."

                val response = llm().execute(
                    prompt("analyze-image") {
                        user {
                            text(userPrompt)
                            image(
                                AttachmentSource.Image(
                                    content = AttachmentContent.Binary.Base64(base64Payload),
                                    format = "png",
                                    mimeType = "image/png"
                                )
                            )
                        }
                    },
                    GoogleModels.Gemini2_5Flash
                )

                call.respond(HttpStatusCode.OK, mapOf("description" to response.textContent()))
            }

            post("/extract-voucher") {
                try {
                    val request = call.receive<VoucherInput>()
                    val result = aiAgent(
                        voucherStrategy(),
                        GoogleModels.Gemini2_5Flash,
                        request
                    )
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        VoucherResult(
                            isVoucher = false,
                            message = "Error al procesar la imagen: ${e.message}"
                        )
                    )
                }
            }
        }
    }
}
