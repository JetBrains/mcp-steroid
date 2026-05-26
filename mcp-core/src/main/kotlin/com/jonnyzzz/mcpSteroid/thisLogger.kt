package com.jonnyzzz.mcpSteroid

import org.slf4j.Logger
import org.slf4j.LoggerFactory

inline fun <reified T : Any> T.thisLogger(): Logger = LoggerFactory.getLogger(T::class.java)

inline fun <reified T : Any> logger(): Logger = LoggerFactory.getLogger(T::class.java)
