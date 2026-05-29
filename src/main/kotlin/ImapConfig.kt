package com

data class ImapConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val ssl: Boolean = true,
    val searchDaysBack: Int = 1,
    val searchDaysForward: Int = 1
)
