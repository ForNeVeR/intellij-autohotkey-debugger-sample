package me.fornever.autohotkey.debugger

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.io.await
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fornever.autohotkey.debugger.dbgp.DbgpClient
import me.fornever.autohotkey.debugger.dbgp.DbgpClientImpl
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel

interface DbgpDebugger {
    /**
     * For the places where request ordering is important (e.g. setting breakpoints and debugger options before debug
     * session initialization). This will make sure that the requests to the debugger service are ordered.
     */
    fun launchInOrder(block: suspend CoroutineScope.() -> Unit)
    
    suspend fun initializeAndResume()
    
    suspend fun setBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean
    suspend fun removeBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean
}

class AutoHotKeyDebugger(val port: Int, parentScope: CoroutineScope) : DbgpDebugger, Disposable {
    
    private val socketChannel = AsynchronousServerSocketChannel.open()
    
    @Suppress("UnstableApiUsage")
    private val scope = parentScope.childScope("AutoHotKey Debugger")
    override fun launchInOrder(block: suspend CoroutineScope.() -> Unit) {
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED, block = block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            logger.error(e)
        }
    }
    
    private val client: Deferred<DbgpClient>

    init {
        socketChannel.bind(InetSocketAddress(port))
        client = scope.async(Dispatchers.IO) {
            val channel = socketChannel.accept().await()
            DbgpClientImpl(scope, channel)
        }
    }

    private val singleAccess = Mutex()
    private suspend fun <T> doAfterConnection(action: suspend (DbgpClient) -> T): T =
        singleAccess.withLock {
            val client = client.await()
            client.sessionInitialized.await()
            action(client)
        }
    
    override fun dispose() {
        scope.cancel()
        socketChannel.close()
    }

    override suspend fun initializeAndResume() =
        doAfterConnection {
            logger.info("Initializing the AutoHotKey debugger.")
            it.run()
        }

    override suspend fun setBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean =
        doAfterConnection {
            logger.info("Setting a breakpoint: $breakpoint.")
            val sourcePosition = breakpoint.sourcePosition ?: return@doAfterConnection false
            val zeroBasedLineNumber = sourcePosition.line
            it.setBreakpoint(sourcePosition.file.toNioPath(), zeroBasedLineNumber + 1)
        }

    override suspend fun removeBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean =
        doAfterConnection {
            logger.info("Removing a breakpoint: $breakpoint.")
            val sourcePosition = breakpoint.sourcePosition ?: return@doAfterConnection false
            val zeroBasedLineNumber = sourcePosition.line
            it.removeBreakpoint(sourcePosition.file.toNioPath(), zeroBasedLineNumber + 1)
        }
}

private val logger = logger<AutoHotKeyDebugger>()