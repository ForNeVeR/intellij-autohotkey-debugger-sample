package me.fornever.lua.debugger.dbgp

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.util.io.toByteArray
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface DbgpClient {
    suspend fun setBreakpoint(file: Path, line: Int)
    suspend fun run()
}

const val defaultBufferSize: Int = 5 // TODO: 5 is for testing only; bump to 1024 for production needs.

class DbgpClientImpl(scope: CoroutineScope, private val socket: AsynchronousSocketChannel) : DbgpClient {

    private val packets = Channel<Any>() // TODO: Correct type for the packet here
    private var buffer = ByteBuffer.allocate(defaultBufferSize)    
    
    init {
        launchSocketReader(scope, socket)
        launchPacketDispatcher(scope)
    }

    override suspend fun setBreakpoint(file: Path, line: Int) {
//        sendCommand("SETB ${file.pathString} $line")
//        sendCommand("LISTB")
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
                // TODO: React on the received info.
            }
        }
    }
    
    private suspend fun dispatchPacketBody(body: ByteArray) {
        // TODO: Deserialize the body from XML.
        val body = body.toString(Charsets.UTF_8)
        logger.trace { "Received packet: $body" }
        packets.send(body)
    }
    
    private val writeMutex = Mutex()
    private suspend fun sendCommand(command: String) {
        writeMutex.withLock { 
            socket.writeSuspending((command + "\n").toByteArray(Charsets.UTF_8))
        }
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
