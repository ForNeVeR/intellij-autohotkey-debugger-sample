package me.fornever.autohotkey.debugger.dbgp

/**
 * Minimal representation of a DBGP <init> packet.
 */
data class DbgpInitPacket(
    val appId: String,
    val ideKey: String,
    val session: String,
    val thread: String,
    val parent: String?,
    val language: String,
    val protocolVersion: String,
    val fileUri: String
)

