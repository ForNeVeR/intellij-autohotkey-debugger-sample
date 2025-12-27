package me.fornever.autohotkey.debugger

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.io.await
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fornever.autohotkey.debugger.dbgp.BreakExecution
import me.fornever.autohotkey.debugger.dbgp.DbgpClient
import me.fornever.autohotkey.debugger.dbgp.DbgpClientImpl
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel

/**
 * ### Notes on Threading
 * All the functions in this class that have the `launch` prefix will schedule an asynchronous activity in the order
 * they were called in.
 * 
 * This is especially important for cases when the user needs to set breakpoints before allowing the debuggee to run — always call the
 * methods in the correct order, and they are guaranteed to take effect in the order they were called.
 */
interface DbgpDebugger {
    /**
     * Will initialize the debugger and resume the debuggee execution for the first time.
     */
    fun launchResumeExecution()
    
    fun launchSetBreakpoint(
        breakpoint: XLineBreakpoint<*>,
        successCallback: (Boolean) -> Unit,
        errorCallback: (Throwable) -> Unit
    )
    fun launchRemoveBreakpoint(breakpoint: XLineBreakpoint<*>)

    fun connectToSession(session: XDebugSession)
}

class AutoHotKeyDebugger(val port: Int, parentScope: CoroutineScope) : DbgpDebugger, Disposable {
    
    private val socketChannel = AsynchronousServerSocketChannel.open()
    
    @Suppress("UnstableApiUsage")
    private val scope = parentScope.childScope("AutoHotKey Debugger")
    private fun launchInOrder(block: suspend CoroutineScope.() -> Unit) {
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

    override fun launchResumeExecution() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            doAfterConnection {
                logger.info("Resume execution.")
                it.run()
            }
        }
    }

    override fun launchSetBreakpoint(
        breakpoint: XLineBreakpoint<*>,
        successCallback: (Boolean) -> Unit,
        errorCallback: (Throwable) -> Unit
    ) {
        launchInOrder {
            val result: Boolean
            try {
                result = doAfterConnection {
                    logger.info("Setting a breakpoint: $breakpoint.")
                    val sourcePosition = breakpoint.sourcePosition ?: return@doAfterConnection false
                    val zeroBasedLineNumber = sourcePosition.line
                    it.setBreakpoint(sourcePosition.file.toNioPath(), zeroBasedLineNumber + 1)
                }
            } catch (e: Throwable) {
                if (e is ControlFlowException || e is CancellationException) throw e
                errorCallback(e)
                return@launchInOrder
            }
            
            successCallback(result)
        }
    }

    override fun launchRemoveBreakpoint(breakpoint: XLineBreakpoint<*>) {
        launchInOrder {
            doAfterConnection {
                logger.info("Removing a breakpoint: $breakpoint.")
                val sourcePosition = breakpoint.sourcePosition ?: run {
                    logger.error("Breakpoint $breakpoint has no source position.")
                    return@doAfterConnection
                }
                
                val zeroBasedLineNumber = sourcePosition.line
                it.removeBreakpoint(sourcePosition.file.toNioPath(), zeroBasedLineNumber + 1)
            }
        }
    }
    
    @Suppress("UnstableApiUsage")
    override fun connectToSession(session: XDebugSession) {
        scope.launch {
            var currentSuspendScope: CoroutineScope? = null
            
            val client = client.await()
            client.events.consumeEach { event ->
                when(event) {
                    BreakExecution -> {
                        // We stop on a new breakpoint, terminate any calculations related to the previous one.
                        currentSuspendScope?.cancel()
                        currentSuspendScope = scope.childScope("AutoHotkeyDebugger: current execution scope")
                        
                        val depth = client.getStackDepth()
                        val stack = AutoHotKeyExecutionStack(
                            currentSuspendScope,
                            client, 
                            client.getStackInfo(0),
                            depth
                        )
                        session.positionReached(AutoHotKeySuspendContext(stack))
                    }
                }
            }
        }
    }
}

private val logger = logger<AutoHotKeyDebugger>()