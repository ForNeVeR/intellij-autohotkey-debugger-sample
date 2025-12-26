package me.fornever.autohotkey.debugger.dbgp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.serialization.XML

object DbgpPropertySerializer : KSerializer<DbgpProperty> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("property")

    private fun parseFromReader(reader: XmlReader): DbgpProperty {
        // We should be positioned at START_ELEMENT for <property>

        // Read all attributes
        var name = ""
        var fullname = ""
        var type = ""
        var classname: String? = null
        var constant: Int? = null
        var children: Int? = null
        var size: Int? = null
        var page: Int? = null
        var pagesize: Int? = null
        var address: Int? = null
        var key: String? = null
        var encoding: String? = null
        var numchildren: Int? = null
        var facet: String? = null

        for (i in 0 until reader.attributeCount) {
            val attrName = reader.getAttributeLocalName(i)
            val attrValue = reader.getAttributeValue(i)
            when (attrName) {
                "name" -> name = attrValue
                "fullname" -> fullname = attrValue
                "type" -> type = attrValue
                "classname" -> classname = attrValue
                "constant" -> constant = attrValue.toIntOrNull()
                "children" -> children = attrValue.toIntOrNull()
                "size" -> size = attrValue.toIntOrNull()
                "page" -> page = attrValue.toIntOrNull()
                "pagesize" -> pagesize = attrValue.toIntOrNull()
                "address" -> address = attrValue.toIntOrNull()
                "key" -> key = attrValue
                "encoding" -> encoding = attrValue
                "numchildren" -> numchildren = attrValue.toIntOrNull()
                "facet" -> facet = attrValue
            }
        }

        // Now read content: either text or child <property> elements
        var value: String? = null
        val properties = mutableListOf<DbgpProperty>()

        // Move to the next event after the start element
        var eventType = reader.next()

        while (eventType != EventType.END_ELEMENT) {
            when (eventType) {
                EventType.TEXT, EventType.CDSECT -> {
                    val text = reader.text.trim()
                    if (text.isNotEmpty()) {
                        value = text
                    }
                }
                EventType.START_ELEMENT -> {
                    if (reader.localName == "property") {
                        // Recursively deserialize child property
                        properties.add(parseFromReader(reader))
                    } else {
                        // Skip unknown elements
                        skipElement(reader)
                    }
                }
                else -> { /* ignore other events */ }
            }
            eventType = reader.next()
        }

        return DbgpProperty(
            name = name,
            fullname = fullname,
            type = type,
            classname = classname,
            constant = constant,
            children = children,
            size = size,
            page = page,
            pagesize = pagesize,
            address = address,
            key = key,
            encoding = encoding,
            numchildren = numchildren,
            facet = facet,
            value = value,
            properties = properties
        )
    }

    private fun skipElement(reader: XmlReader) {
        var depth = 1
        while (depth > 0) {
            when (reader.next()) {
                EventType.START_ELEMENT -> depth++
                EventType.END_ELEMENT -> depth--
                else -> { }
            }
        }
    }

    override fun deserialize(decoder: Decoder): DbgpProperty {
        // Cast to XmlInput to access the underlying XmlReader
        val xmlInput = decoder as XML.XmlInput
        val reader = xmlInput.input
        return parseFromReader(reader)
    }

    override fun serialize(encoder: Encoder, value: DbgpProperty) {
        throw UnsupportedOperationException("Serialization of DbgpProperty is not implemented")
    }
}
