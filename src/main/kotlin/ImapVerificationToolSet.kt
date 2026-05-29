package com

import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

@LLMDescription("Herramientas para verificar operaciones bancarias en correos electrónicos via IMAP")
class ImapVerificationToolSet(private val imapService: ImapMailService) : ToolSet {

    @Tool
    @LLMDescription("Busca en el correo electrónico (IMAP) si existe una operación con el número y fecha especificados")
    fun verifyPaymentInEmail(
        @LLMDescription("Fecha de la operación en formato YYYY-MM-DD") date: String,
        @LLMDescription("Número de operación o transacción a buscar") operationNumber: String
    ): String {
        return try {
            val exists = imapService.verifyOperationExists(date, operationNumber)
            exists.toString()
        } catch (e: Exception) {
            "false"
        }
    }
}
