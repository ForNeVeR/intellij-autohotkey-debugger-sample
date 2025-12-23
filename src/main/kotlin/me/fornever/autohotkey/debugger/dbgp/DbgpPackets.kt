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
@XmlSerialName("response")
data class DbgpResponse(
    @XmlElement(false) val command: String,
    @XmlElement(false) @SerialName("transaction_id") val transactionId: Int,
    @XmlElement(false) val state: String,
    @XmlElement(false) val resolved: String? = null,
    @XmlElement(false) val id: String
) : DbgpPacket
