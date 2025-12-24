package me.fornever.autohotkey.debugger.dbgp

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.util.io.toByteArray
import com.jetbrains.rd.util.concurrentMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.pathString

interface DbgpClient {
    val sessionInitialized: Deferred<Unit>
    suspend fun setBreakpoint(file: Path, oneBasedLine: Int): Boolean
    suspend fun removeBreakpoint(file: Path, oneBasedLine: Int): Boolean
    suspend fun run()
}

const val defaultBufferSize: Int = 5 // TODO: 5 is for testing only; bump to 1024 for production needs.

class DbgpClientImpl(scope: CoroutineScope, private val socket: AsynchronousSocketChannel) : DbgpClient {

    private val packets = Channel<DbgpPacket>(capacity = Channel.UNLIMITED)
    private val responses = MutableSharedFlow<DbgpResponse>()
    private var buffer = ByteBuffer.allocate(defaultBufferSize)
    private val initialized = CompletableDeferred<Unit>()
    
    init {
        launchSocketReader(scope, socket)
        launchPacketDispatcher(scope)
    }

    override val sessionInitialized: Deferred<Unit> = initialized

    data class BreakpointDefinition(val file: Path, val line: Int)
    private val activeBreakpoints = concurrentMapOf<BreakpointDefinition, String>()
    
    override suspend fun setBreakpoint(file: Path, oneBasedLine: Int): Boolean {
        assert(oneBasedLine > 0) { "Line number must be positive." }
        
        val result = command("breakpoint_set", "-t", "line",  "-f", file.pathString, "-n", oneBasedLine.toString())
        activeBreakpoints[BreakpointDefinition(file, oneBasedLine)] = result.id 
        return result.state == "enabled"
    }

    override suspend fun removeBreakpoint(file: Path, oneBasedLine: Int): Boolean {
        assert(oneBasedLine > 0) { "Line number must be positive." }
        
        val id = activeBreakpoints.remove(BreakpointDefinition(file, oneBasedLine)) ?: return false 

        command("breakpoint_remove", "-d", id)
        return true
    }

    override suspend fun run() {
//        sendCommand("RUN")
    }

    private fun launchSocketReader(scope: CoroutineScope, socket: AsynchronousSocketChannel) {
        scope.launch(CoroutineName("MobDebug socket reader")) {
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
        scope.launch(CoroutineName("MobDebug packet dispatcher")) {
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
    
    private suspend fun dispatchPacketBody(body: ByteArray) {
        val xml = body.toString(Charsets.UTF_8)
        try {
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
    suspendCancellableCoroutine {
        // On cancellation, we just abandon the operation — this means the socket is about to die soon anyway.
        read(buffer, null, object : CompletionHandler<Int, Nothing?> {
            override fun completed(result: Int?, attachment: Nothing?) {
                try {
                    it.resume(result!!)
                } catch (e: Throwable) {
                    logger.error(e)
                }
            }

            override fun failed(exc: Throwable?, attachment: Nothing?) {
                try {
                    it.resumeWithException(exc!!)
                } catch (e: Throwable) {
                    logger.error(e)
                }
            }
        })
}

private suspend fun AsynchronousSocketChannel.writeSuspending(data: ByteArray) {
    suspendCancellableCoroutine {
        // On cancellation, we just abandon the operation — this means the socket is about to die soon anyway.
        write(ByteBuffer.wrap(data), null, object : CompletionHandler<Int, Nothing?> {
            override fun completed(result: Int?, attachment: Nothing?) {
                try {
                    it.resume(Unit)
                } catch (e: Throwable) {
                    logger.error(e)
                }
            }

            override fun failed(exc: Throwable?, attachment: Nothing?) {
                try {
                    it.resumeWithException(exc!!)
                } catch (e: Throwable) {
                    logger.error(e)
                }
            }
        })
    }
}

private val logger = logger<DbgpClientImpl>()
