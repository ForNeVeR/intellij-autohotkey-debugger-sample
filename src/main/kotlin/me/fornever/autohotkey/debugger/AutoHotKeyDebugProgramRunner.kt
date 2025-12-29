package me.fornever.autohotkey.debugger

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.rd.util.toPromise
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.*
import com.jetbrains.rd.framework.util.NetUtils
import kotlinx.coroutines.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.concurrency.Promise

class AutoHotKeyDebugProgramRunner(private val scope: CoroutineScope) : AsyncProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): @NonNls String = "AutoHotKeyDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is AutoHotKeyRunConfiguration

    @ExperimentalCoroutinesApi
    override fun execute(
        environment: ExecutionEnvironment,
        state: RunProfileState
    ): Promise<RunContentDescriptor?> = scope.async {
        saveAllDocuments()
        val session = createDebugSession(environment, state as AutoHotKeyRunProfileState)
        session.runContentDescriptor
    }.toPromise()

    suspend fun createDebugSession(
        environment: ExecutionEnvironment,
        state: AutoHotKeyRunProfileState,
        listener: XDebugSessionListener? = null
    ): XDebugSession {
        val debuggerManager = XDebuggerManager.getInstance(environment.project)
        val debugger = startDebugServer()
        try {
            val processHandler = state.startDebugProcess(debugger.port)
            return withContext(Dispatchers.EDT) {
                debuggerManager.startSession(environment, object : XDebugProcessStarter() {
                    override fun start(session: XDebugSession): XDebugProcess {
                        listener?.let { session.addSessionListener(it) }
                        return AutoHotKeyDebugProcess(session, processHandler, debugger)
                    }
                })
            }
        } catch (e: Exception) {
            Disposer.dispose(debugger)
            throw e
        }
    }

    private suspend fun startDebugServer(): AutoHotKeyDebugger {
        return withContext(Dispatchers.IO) {
            val port = NetUtils.findFreePort(9000)
            AutoHotKeyDebugger(port, scope)
        }
    }
}

private suspend fun saveAllDocuments() = withContext(Dispatchers.EDT) {
    FileDocumentManager.getInstance().saveAllDocuments()
}
