package me.fornever.autohotkey.debugger.dbgp

import nl.adaptivity.xmlutil.serialization.XML

object DbgpPacketParser {
    private val xml = XML {
        recommended_0_90_2()
    }

    fun parse(xmlString: String): DbgpPacket {
        return xml.decodeFromString(DbgpPacket.serializer(), xmlString)
    }
}
