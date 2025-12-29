package me.fornever.autohotkey.debugger.dbgp

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.io.toByteArray
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.jetbrains.rd.util.concurrentMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.pathString

sealed interface DbgpClientEvent
data class BreakExecution(val breakpoint: XLineBreakpoint<*>?) : DbgpClientEvent

interface DbgpClient {
    suspend fun setBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean
    suspend fun removeBreakpoint(breakpoint: XLineBreakpoint<*>)
    suspend fun run()
    
    suspend fun getStackDepth(): Int
    suspend fun getStackInfo(depth: Int): DbgpStackInfo
    suspend fun getAllContexts(): List<DbgpContextInfo>
    suspend fun getProperties(depth: Int, contextId: Int): List<DbgpPropertyInfo>
    
    suspend fun getProperty(property: DbgpPropertyInfo, stackDepth: Int): DbgpPropertyInfo
    suspend fun setProperty(property: DbgpPropertyInfo, stackDepth: Int, value: String)
    
    val sessionInitialized: Deferred<Unit>
    val events: Channel<DbgpClientEvent>
}

data class DbgpStackInfo(val file: Path, val symbolName: String?, val oneBasedLineNumber: Int) {
    companion object {
        internal fun of(stack: DbgpStack): DbgpStackInfo {
            val uri = URI.create(stack.filename)
            return DbgpStackInfo(Path.of(uri), stack.where, stack.lineno)
        }
    }
}

data class DbgpContextInfo(val id: Int, val name: @NlsSafe String) {
    companion object {
        internal fun of(stack: DbgpContext): DbgpContextInfo = DbgpContextInfo(stack.id, stack.name)
    }
}

data class DbgpPropertyInfo(
    val type: String,
    val fullName: String,
    val name: String,
    val value: String?,
    val children: List<DbgpPropertyInfo>
) {
    companion object {
        internal fun of(property: DbgpProperty): DbgpPropertyInfo = DbgpPropertyInfo(
            property.type,
            property.fullname,
            property.name,
            property.value?.let {
                Base64.getDecoder().decode(it).toString(Charsets.UTF_8)
            },
            property.properties.map(DbgpPropertyInfo::of)
        )
    }
}

const val defaultBufferSize: Int = 1024

class DbgpClientImpl(scope: CoroutineScope, private val socket: AsynchronousSocketChannel) : DbgpClient {

    private val packets = Channel<DbgpPacket>(capacity = Channel.UNLIMITED)
    private val responses = MutableSharedFlow<DbgpResponse>()
    private var buffer = ByteBuffer.allocate(defaultBufferSize)
    private val initialized = CompletableDeferred<Unit>()
    
    init {
        launchSocketReader(scope, socket)
        launchPacketDispatcher(scope)
        launchResponseDecoder(scope)
    }

    override val sessionInitialized: Deferred<Unit> = initialized
    override val events = Channel<DbgpClientEvent>()

    private val activeBreakpoints = concurrentMapOf<XLineBreakpoint<*>, String>()

    override suspend fun setBreakpoint(breakpoint: XLineBreakpoint<*>): Boolean {
        val sourcePosition = breakpoint.sourcePosition ?: return false
        val zeroBasedLine = sourcePosition.line
        assert(zeroBasedLine >= 0) { "Line number must be non-negative." }

        val file = sourcePosition.file.toNioPath()
        val oneBasedLine = zeroBasedLine + 1
        val result = command("breakpoint_set", "-t", "line", "-f", file.pathString, "-n", oneBasedLine.toString())
        activeBreakpoints[breakpoint] = result.id!!
        return result.state == "enabled"
    }

    override suspend fun removeBreakpoint(breakpoint: XLineBreakpoint<*>) {
        val id = activeBreakpoints.remove(breakpoint) ?: run {
            logger.warn("No active breakpoint found for $breakpoint.")
            return
        }
        command("breakpoint_remove", "-d", id)
    }

    override suspend fun run() {
        command("run")
    }

    override suspend fun getStackDepth(): Int {
        val response = command("stack_depth")
        return response.depth!!
    }

    override suspend fun getStackInfo(depth: Int): DbgpStackInfo {
        val response = command("stack_get", "-d", depth.toString())
        return DbgpStackInfo.of(response.stack.single())
    }

    override suspend fun getAllContexts(): List<DbgpContextInfo> {
        val response = command("context_names")
        return response.contexts.map(DbgpContextInfo::of)
    }

    override suspend fun getProperties(depth: Int, contextId: Int): List<DbgpPropertyInfo> {
        val response = command("context_get", "-d", depth.toString(), "-c", contextId.toString())
        return response.properties.map(DbgpPropertyInfo::of)
    }

    override suspend fun getProperty(
        property: DbgpPropertyInfo,
        stackDepth: Int
    ): DbgpPropertyInfo {
        val response = command("property_get", "-n", property.fullName, "-d", stackDepth.toString())
        return DbgpPropertyInfo.of(response.properties.single())
    }

    override suspend fun setProperty(
        property: DbgpPropertyInfo,
        stackDepth: Int,
        value: String
    ) {
        val encodedValue = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val response = command(
            "property_set",
            "-n",
            property.fullName,
            "-d",
            stackDepth.toString(),
            "-l",
            encodedValue.length.toString(),
            "--",
            encodedValue
        )
        if (response.success != 1) error("Cannot set property value.")
    }

    private fun launchSocketReader(scope: CoroutineScope, socket: AsynchronousSocketChannel) {
        scope.launch(CoroutineName("DBGP socket reader")) {
            while (true) {
                val packet = readPacketBody(socket) ?: break
                dispatchPacketBody(packet)
            }
        }
    }
    
    private suspend fun readPacketBody(socket: AsynchronousSocketChannel): ByteArray? {
        var lengthSeparator: Int? = null
        var dataSeparator: Int? = null
        
        while (lengthSeparator == null || dataSeparator == null) {
            val position = buffer.position()
            val bytesRead = socket.readSuspending(buffer)
            if (bytesRead == -1) return null
            if (bytesRead == 0) {
                assert(buffer.remaining() == 0)
                
                logger.trace { "Increasing buffer capacity from ${buffer.capacity()} to ${buffer.capacity() * 2}." }
                val newBuffer = ByteBuffer.allocate(buffer.capacity() * 2)
                buffer.flip()
                newBuffer.put(buffer)
                buffer = newBuffer
                continue
            }
            
            for (i in 0 until bytesRead) {
                val byte = buffer.get(position + i)
                if (byte == 0.toByte()) {
                    if (lengthSeparator == null) lengthSeparator = position + i
                    else {
                        dataSeparator = position + i
                        break
                    }
                }
            }
        }
        
        val lengthBuffer = buffer.slice(0, lengthSeparator)
        val dataArray = buffer.slice(lengthSeparator + 1, dataSeparator - lengthSeparator - 1).toByteArray()
        val length = lengthBuffer.toByteArray().toString(Charsets.UTF_8).toInt()
        assert(dataArray.size == length) { "Data buffer size doesn't match length: ${dataArray.size} != $length." }
        
        // Now we trim our own data from the buffer, but leave any additional data read from the next packet intact:
        val newBuffer = ByteBuffer.allocate(maxOf(defaultBufferSize, buffer.position() - dataSeparator - 1))
        newBuffer.put(buffer.slice(dataSeparator + 1, buffer.position() - dataSeparator - 1))
        buffer = newBuffer
        
        return dataArray
    }

    private fun launchPacketDispatcher(scope: CoroutineScope) {
        scope.launch(CoroutineName("DBGP packet dispatcher")) {
            while (true) {
                val packet = packets.receive()
                try {
                    when (packet) {
                        is DbgpInit -> initialized.complete(Unit)
                        is DbgpResponse -> {
                            responses.emit(packet)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is ControlFlowException || e is CancellationException) throw e
                    logger.error("Unable to process the packet $packet.", e)
                }
            }
        }
    }

    private fun launchResponseDecoder(scope: CoroutineScope) {
        scope.launch(CoroutineName("DBGP response decoder"), CoroutineStart.UNDISPATCHED) {
            responses.collect { response ->
                logger.trace { "Processing response: $response" }
                if (response.command == "run" && response.status == "break") {
                    logger.trace("We hit a breakpoint. Getting the stack trace.")
                    
                    val top = getStackInfo(0)
                    val breakpoint = activeBreakpoints.keys.firstOrNull { bp ->
                        val sp = bp.sourcePosition
                        sp != null &&
                            sp.file.toNioPath() == top.file &&
                            sp.line == top.oneBasedLineNumber - 1
                    }

                    logger.trace("Found breakpoint: $breakpoint.")
                    
                    events.send(BreakExecution(breakpoint))
                }
            }
        }
    }
    
    private suspend fun dispatchPacketBody(body: ByteArray) {
        val xml = body.toString(Charsets.UTF_8)
        try {
            logger.trace { "Received packet:\n$xml" }
            val packet = DbgpPacketParser.parse(xml)
            packets.send(packet)
        } catch (e: Throwable) {
            if (e is ControlFlowException || e is CancellationException) throw e
            logger.error("Failed to parse DBGP packet:\n$xml", e)
        }
    }
    
    private val writeMutex = Mutex()
    private var lastTransactionId = 0
    private suspend fun command(command: String, vararg args: String): DbgpResponse =
        writeMutex.withLock {
            val transactionId = lastTransactionId++
            val response = responses.filter { it.transactionId == transactionId }
            val commandList = listOf(command) + listOf("-i", transactionId.toString()) + args
            val fullCommand = commandList.joinToString(" ") + "\u0000" // TODO: escape the arguments as needed
            logger.trace { "Sending command: $fullCommand" }
            socket.writeSuspending(fullCommand.toByteArray(Charsets.UTF_8))
            response.first()
        }
}

private suspend fun AsynchronousSocketChannel.readSuspending(buffer: ByteBuffer): Int =
    awaitChannel { handler -> read(buffer, null, handler) }

private suspend fun AsynchronousSocketChannel.writeSuspending(data: ByteArray) {
    // Ignore the result (number of bytes written) to preserve previous behavior
    awaitChannel<Int> { handler -> write(ByteBuffer.wrap(data), null, handler) }
}

private suspend fun <T> awaitChannel(
    start: (CompletionHandler<T, Nothing?>) -> Unit
): T = suspendCancellableCoroutine { cont ->
    // On cancellation, we just abandon the operation — this means the socket is about to die soon anyway.
    start(object : CompletionHandler<T, Nothing?> {
        override fun completed(result: T?, attachment: Nothing?) {
            try {
                cont.resume(result!!)
            } catch (e: Throwable) {
                logger.error(e)
            }
        }

        override fun failed(exc: Throwable?, attachment: Nothing?) {
            try {
                if (exc is IOException && cont.isCancelled) {
                    cont.cancel()
                } else {
                    cont.resumeWithException(exc!!)
                }
            } catch (e: Throwable) {
                logger.error(e)
            }
        }
    })
}

private val logger = logger<DbgpClientImpl>()
