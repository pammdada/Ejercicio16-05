package com

import ai.koog.ktor.*
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.GoogleLLMProvider
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    install(ContentNegotiation) {
        json()
    }

    install(Koog)

    routing {
        get("/hola"){
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

                val model = LLModel(GoogleLLMProvider, "gemini-1.5-flash")

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
                    model
                )

                call.respond(HttpStatusCode.OK, mapOf("description" to response.textContent()))
            }
        }
    }
}
