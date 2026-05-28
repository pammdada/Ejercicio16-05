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
                    system("""Eres un clasificador de imágenes contables. Tu única tarea es determinar si la imagen es un comprobante de pago, boleta, factura, ticket o voucher. Responde EXCLUSIVAMENTE con la palabra true o false. No incluyas ningún otro texto, explicación o formato.""")
                    user {
                        //validar las transacciones. usar ejemplos.
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
                    system("""Eres un asistente experto en extracción de datos contables de comprobantes de pago (boletas, facturas, recibos). Analiza la imagen o texto provisto y genera un JSON limpio bajo las siguientes reglas:

REGLAS DE EXTRACCIÓN Y NORMALIZACIÓN:
1. Clasificación por Comprobante:
   - Si es FACTURA, busca obligatoriamente RUC y RAZÓN SOCIAL del cliente.
   - Si es BOLETA DE VENTA, busca DNI y NOMBRE del cliente.
2. Método de Pago: Clasifícalo estrictamente en uno de estos valores: [EFECTIVO, TARJETA_CREDITO, TARJETA_DEBITO, TRANSFERENCIA, YAPE, PLIN, OTRO].
3. Fecha y Hora (Separados):
   - "fecha": Extrae solo la fecha y normalízala al formato "YYYY-MM-DD" (ej: "2026-05-27").
   - "hora": Extrae solo la hora en formato de 24 horas "HH:mm" (ej: "14:35"). Si el comprobante no tiene hora visible, asígnale null.
4. Formatos base:
   - "monto": Extrae solo el valor numérico como texto (ej: "45.00"), sin símbolos de moneda.
   - "moneda": Usa el estándar ISO de 3 letras (PEN, USD).
5. Valores Ausentes: Si un campo no existe en el comprobante, asígnale estrictamente el valor null (sin comillas). No inventes datos.

INSTRUCCIÓN DE SALIDA CRÍTICA:
Responde EXCLUSIVAMENTE con el objeto JSON estructurado. Está prohibido incluir bloques de código Markdown (```json ... ```), texto introductorio o explicaciones. Devuelve solo el texto plano del JSON que empiece con { y termine con }.

ESTRUCTURA DEL JSON:
{
  "descripcion": "Descripción breve (max 6 palabras)",
  "monto": "Monto numérico como String",
  "numeroTransaccion": "Número de comprobante o ID de operación",
  "moneda": "PEN o USD",
  "fecha": "YYYY-MM-DD o null",
  "hora": "HH:mm o null",
  "metodoPago": "Método estandarizado",
  "datosPersonales": {
    "nombre": "Nombre completo o null",
    "dni": "DNI de 8 dígitos o null",
    "ruc": "RUC de 11 dígitos o null",
    "email": "Correo electrónico o null",
    "razonSocial": "Razón social o null"
  }
}""")
                    user {
                        //usar timestamp, fecha y hora separadas, definir metodo de pago, tipo de comprobante
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