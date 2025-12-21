package me.fornever.lua.debugger

import com.intellij.openapi.Disposable
import com.intellij.platform.util.coroutines.childScope
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

interface DbgpDebugger {
    suspend fun setBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean
    suspend fun removeBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean
}

class LuaDebugger(val port: Int, parentScope: CoroutineScope) : DbgpDebugger, Disposable {
    
    private val socketChannel: ServerSocketChannel = ServerSocketChannel.open()
    
    @Suppress("UnstableApiUsage")
    val scope = parentScope.childScope("Lua debugger")
    private val channel: Deferred<SocketChannel>

    init {
        socketChannel.configureBlocking(false)
        socketChannel.bind(InetSocketAddress(port))
        channel = scope.async(Dispatchers.IO) {
            runInterruptible {
                socketChannel.accept()
            }
        }
    }

    private val singleAccess = Mutex()
    
    override fun dispose() {
        scope.cancel()
        socketChannel.close()
    }

    override suspend fun setBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean =
        singleAccess.withLock { 
            TODO("Not yet implemented")
        }

    override suspend fun removeBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean =
        singleAccess.withLock {
            TODO("Not yet implemented")
        }
}
