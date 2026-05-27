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
    val originalInput: VoucherInput
)

private val json = Json { ignoreUnknownKeys = true }

private fun parseImageMeta(imageBase64: String): Pair<String, String> {
    val regex = Regex("""^data:image/(\w+);base64,""")
    val match = regex.find(imageBase64)
    return if (match != null) {
        val format = match.groupValues[1].lowercase()
        val mimeType = "image/$format"
        format to mimeType
    } else {
        "png" to "image/png"
    }
}

private fun voucherStrategy(): AIAgentGraphStrategy<VoucherInput, VoucherResult> {
    return strategy("extract-voucher") {

        val classifyNode by node<VoucherInput, ClassificationResult>("classify") { input ->
            val (format, mimeType) = parseImageMeta(input.imageBase64)
            val base64Payload = input.imageBase64.substringAfter(",")
            llm.writeSession {
                appendPrompt {
                    user {
                        //validar las transacciones. usar ejemplos.
                        text("""¿Es esta imagen un comprobante de pago, boleta, factura, ticket o voucher? Responde ÚNICAMENTE con la palabra true o false.""")
                        image(
                            AttachmentSource.Image(
                                //format y mimetype a partir de la imagen en base64
                                //colocar un ejemplo en el prompt, cargar voucher en local, revisar documentación prompts
                                content = AttachmentContent.Binary.Base64(base64Payload),
                                format = format,
                                mimeType = mimeType
                            )
                        )
                    }
                }

                //devolver solo TRUE/FALSE no JSON
                val response = requestLLMWithoutTools()
                val rawText = response.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                println("[DEBUG classifyNode] Raw LLM response: $rawText")
                val isV = rawText.trim().lowercase() == "true"
                ClassificationResult(isV, input)
            }
        }

        val extractNode by node<ClassificationResult, VoucherData>("extract") { result ->
            if (!result.isVoucher) error("Not a voucher")
            val (format, mimeType) = parseImageMeta(result.originalInput.imageBase64)
            val base64Payload = result.originalInput.imageBase64.substringAfter(",")
            llm.writeSession {
                appendPrompt {
                    //https://docs.koog.ai/prompts/prompt-creation/#basic-structure
                    //usar user, system y assistent para
                    user {
                        //usar timestamp, fecha y hora separadas, definir metodo de pago, tipo de comprobante
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
                                format = format,
                                mimeType = mimeType
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

        route("/ai") {

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