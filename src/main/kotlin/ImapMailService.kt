package com

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.AndTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.SearchTerm
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Properties

class ImapMailService(private val config: ImapConfig) {

    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.host)
            put("mail.imaps.port", config.port.toString())
            put("mail.imaps.ssl.enable", config.ssl.toString())
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "10000")
        }
        Session.getInstance(props)
    }

    fun verifyOperationExists(dateIso: String, operationNumber: String): Boolean {
        val date = LocalDate.parse(dateIso)
        val startDate = Date.from(date.minusDays(config.searchDaysBack.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant())
        val endDate = Date.from(date.plusDays(config.searchDaysForward.toLong()).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant())

        val store = session.getStore("imaps")
        try {
            store.connect(config.host, config.username, config.password)

            val folder = store.getFolder("INBOX")
            try {
                folder.open(Folder.READ_ONLY)

                val dateTerm: SearchTerm = AndTerm(
                    ReceivedDateTerm(ComparisonTerm.GE, startDate),
                    ReceivedDateTerm(ComparisonTerm.LE, endDate)
                )

                val messages = folder.search(dateTerm)

                for (message in messages) {
                    val content = getTextFromMessage(message)
                    if (content.contains(operationNumber, ignoreCase = true)) {
                        return true
                    }
                }

                return false
            } finally {
                if (folder.isOpen) folder.close(false)
            }
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    private fun getTextFromMessage(message: Message): String {
        return try {
            when {
                message.contentType?.contains("text/plain", ignoreCase = true) == true -> {
                    message.content.toString()
                }
                message.content is Multipart -> {
                    val multipart = message.content as Multipart
                    val sb = StringBuilder()
                    for (i in 0 until multipart.count) {
                        val bodyPart = multipart.getBodyPart(i)
                        val ct = bodyPart.contentType ?: ""
                        if (ct.contains("text/plain", ignoreCase = true) || ct.contains("text/html", ignoreCase = true)) {
                            sb.appendLine(bodyPart.content.toString())
                        }
                        if (bodyPart.content is Multipart) {
                            sb.appendLine(getTextFromPart(bodyPart.content as Multipart))
                        }
                    }
                    sb.toString()
                }
                else -> {
                    message.content.toString()
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun getTextFromPart(multipart: Multipart): String {
        val sb = StringBuilder()
        for (i in 0 until multipart.count) {
            try {
                val bodyPart = multipart.getBodyPart(i)
                val ct = bodyPart.contentType ?: ""
                if (ct.contains("text/plain", ignoreCase = true) || ct.contains("text/html", ignoreCase = true)) {
                    sb.appendLine(bodyPart.content.toString())
                }
                if (bodyPart.content is Multipart) {
                    sb.appendLine(getTextFromPart(bodyPart.content as Multipart))
                }
            } catch (_: Exception) {}
        }
        return sb.toString()
    }
}
