package me.fornever.autohotkey.debugger.dbgp

import nl.adaptivity.xmlutil.serialization.XML

object DbgpInitPacketParser {
    private val xml = XML {
        recommended_0_90_2()
    }

    /**
     * Attempts to parse the provided XML bytes as a DBGP <init> packet.
     * Returns null if parsing fails.
     */
    fun parse(xmlString: String): DbgpInitPacket {
        return xml.decodeFromString(DbgpInitPacket.serializer(), xmlString)
    }
}
