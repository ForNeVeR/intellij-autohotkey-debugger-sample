package me.fornever.autohotkey.debugger.dbgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DbgpPropertyParserTest {

    @Test
    fun parsePropertyWithTextValue() {
        val xml = """
            <response command="context_get" transaction_id="1">
                <property children="0" encoding="base64" facet="Builtin" fullname="A_Index" name="A_Index" size="1" type="integer">MQ==</property>
            </response>
        """.trimIndent()

        val packet = DbgpPacketParser.parse(xml) as DbgpResponse
        assertEquals(1, packet.properties.size)

        val prop = packet.properties[0]
        assertEquals("A_Index", prop.name)
        assertEquals("A_Index", prop.fullname)
        assertEquals("integer", prop.type)
        assertEquals("base64", prop.encoding)
        assertEquals("MQ==", prop.value)
        assertEquals(0, prop.children)
        assertEquals(emptyList<DbgpProperty>(), prop.properties)
    }

    @Test
    fun parsePropertyWithChildProperties() {
        val xml = """
            <response command="context_get" transaction_id="1">
                <property address="9478224" children="1" classname="Class" facet="" fullname="Any" name="Any" numchildren="2" page="0" pagesize="20" size="0" type="object">
                    <property address="9478128" children="1" classname="Prototype" facet="" fullname="Any.base" name="base" numchildren="2" page="0" pagesize="20" size="0" type="object"></property>
                </property>
            </response>
        """.trimIndent()

        val packet = DbgpPacketParser.parse(xml) as DbgpResponse
        assertEquals(1, packet.properties.size)

        val prop = packet.properties[0]
        assertEquals("Any", prop.name)
        assertEquals("object", prop.type)
        assertNull(prop.value)
        assertEquals(1, prop.properties.size)

        val childProp = prop.properties[0]
        assertEquals("base", childProp.name)
        assertEquals("Prototype", childProp.classname)
    }

    @Test
    fun parseDeeplyNestedProperties() {
        val xml = """
            <response command="context_get" transaction_id="1">
                <property children="1" fullname="root" name="root" type="object">
                    <property children="1" fullname="root.child" name="child" type="object">
                        <property children="0" fullname="root.child.leaf" name="leaf" type="string" encoding="base64">dGVzdA==</property>
                    </property>
                </property>
            </response>
        """.trimIndent()

        val packet = DbgpPacketParser.parse(xml) as DbgpResponse
        val root = packet.properties[0]
        assertEquals("root", root.name)

        val child = root.properties[0]
        assertEquals("child", child.name)

        val leaf = child.properties[0]
        assertEquals("leaf", leaf.name)
        assertEquals("dGVzdA==", leaf.value)
    }

    @Test
    fun parsePropertyWithNoValueAndNoChildren() {
        val xml = """
            <response command="context_get" transaction_id="1">
                <property children="0" fullname="empty" name="empty" type="undefined"></property>
            </response>
        """.trimIndent()

        val packet = DbgpPacketParser.parse(xml) as DbgpResponse
        val prop = packet.properties[0]
        assertEquals("empty", prop.name)
        assertNull(prop.value)
        assertEquals(emptyList<DbgpProperty>(), prop.properties)
    }
}
