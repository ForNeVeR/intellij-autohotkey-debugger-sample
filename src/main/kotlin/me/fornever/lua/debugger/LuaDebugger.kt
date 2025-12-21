package me.fornever.lua.debugger

import com.intellij.openapi.Disposable
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

interface DbgpDebugger {
    val port: Int
}

class LuaDebugger(override val port: Int) : DbgpDebugger, Disposable {
    private val socketChannel: ServerSocketChannel = ServerSocketChannel.open()

    init {
        socketChannel.configureBlocking(false)
        socketChannel.bind(InetSocketAddress(port))
    }

    override fun dispose() {
        socketChannel.close()
    }
}
