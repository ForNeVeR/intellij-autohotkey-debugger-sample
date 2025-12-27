package me.fornever.autohotkey.debugger.dbgp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
sealed interface DbgpPacket

@Serializable
@XmlSerialName("init")
data class DbgpInit(
    @XmlElement(false) @SerialName("appid") val appId: String,
    @XmlElement(false) @SerialName("ide_key") val ideKey: String,
    @XmlElement(false) val session: String,
    @XmlElement(false) val thread: String,
    @XmlElement(false) val parent: String? = null,
    @XmlElement(false) val language: String,
    @XmlElement(false) @SerialName("protocol_version") val protocolVersion: String,
    @XmlElement(false) @SerialName("fileuri") val fileUri: String
) : DbgpPacket

@Serializable
@XmlSerialName("input")
data class DbgpStackInput(
    @XmlElement(false) val level: Int,
    @XmlElement(false) val type: String,
    @XmlElement(false) val filename: String,
    @XmlElement(false) val lineno: Int
)

@Serializable
@XmlSerialName("stack")
data class DbgpStack(
    @XmlElement(false) val level: Int,
    @XmlElement(false) val type: String,
    @XmlElement(false) val filename: String,
    @XmlElement(false) val lineno: Int,
    @XmlElement(false) val where: String? = null,
    @XmlElement(false) val cmdbegin: String? = null,
    @XmlElement(false) val cmdend: String? = null,
    val input: DbgpStackInput? = null
)

@Serializable
@XmlSerialName("context")
data class DbgpContext(
    @XmlElement(false) val name: String,
    @XmlElement(false) val id: Int
)

@Serializable(with = DbgpPropertySerializer::class)
@XmlSerialName("property")
data class DbgpProperty(
    val name: String,
    val fullname: String,
    val type: String,
    val classname: String? = null,
    val constant: Int? = null,
    val children: Int? = null,
    val size: Int? = null,
    val page: Int? = null,
    val pagesize: Int? = null,
    val address: Int? = null,
    val key: String? = null,
    val encoding: String? = null,
    val numchildren: Int? = null,
    val facet: String? = null,
    val value: String? = null,
    val properties: List<DbgpProperty> = emptyList()
)

@Serializable
@XmlSerialName("response")
data class DbgpResponse(
    @XmlElement(false) val command: String,
    @XmlElement(false) @SerialName("transaction_id") val transactionId: Int,
    @XmlElement(false) val state: String? = null,
    @XmlElement(false) val status: String? = null,
    @XmlElement(false) val success: Int? = null,
    @XmlElement(false) val resolved: String? = null,
    @XmlElement(false) val reason: String? = null,
    @XmlElement(false) val id: String? = null,
    @XmlElement(false) val depth: Int? = null,
    @XmlElement(false) val context: Int? = null,
    val stack: List<DbgpStack> = emptyList(),
    val contexts: List<DbgpContext> = emptyList(),
    val properties: List<DbgpProperty> = emptyList()
) : DbgpPacket
