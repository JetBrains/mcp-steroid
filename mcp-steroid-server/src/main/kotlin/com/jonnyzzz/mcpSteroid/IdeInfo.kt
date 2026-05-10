package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

@Serializable
data class IdeInfo(
    val name: String,
    val version: String,
    val build: String
)
