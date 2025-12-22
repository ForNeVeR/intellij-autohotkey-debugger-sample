package me.fornever.autohotkey.debugger.dbgp

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object DbgpInitPacketParser {
    /**
     * Attempts to parse the provided XML bytes as a DBGP <init> packet.
     * Returns null if the root element is not <init>.
     */
    fun tryParse(xml: ByteArray): DbgpInitPacket? {
        val dbf = DocumentBuilderFactory.newInstance()
        
        // Harden the parser a bit; we only need attributes.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false)
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        dbf.isExpandEntityReferences = false

        val builder = dbf.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xml))
        val root = doc.documentElement ?: return null
        if (root.tagName.lowercase() != "init") return null

        return DbgpInitPacket(
            appId = root.getAttr("appid"),
            ideKey = root.getAttr("idekey"),
            session = root.getAttr("session"),
            thread = root.getAttr("thread"),
            parent = root.getAttrOrNull("parent"),
            language = root.getAttr("language"),
            protocolVersion = root.getAttr("protocol_version"),
            fileUri = root.getAttr("fileuri")
        )
    }

    private fun Element.getAttr(name: String): String = this.getAttribute(name)
    private fun Element.getAttrOrNull(name: String): String? = this.getAttribute(name).ifEmpty { null }
}