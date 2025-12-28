package me.fornever.autohotkey.debugger

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.execution.impl.RunnerAndConfigurationSettingsImpl
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestLoggerFactory
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.disposableFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerTestUtil
import kotlinx.coroutines.runBlocking
import me.fornever.autohotkey.debugger.dbgp.DbgpClientImpl
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.Semaphore
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration.Companion.seconds

@TestApplication
class DebuggerTest {
    
    @Test
    fun testDebuggerStopsAtBreakpointOnExpectedLine() {
        val file = copyAndOpenFile("debugger/script.ahk")
        val oneBasedLine = 11 // currentMessage := "Iteration number: " . index
        val zeroBasedLine = oneBasedLine - 1
        
        XDebuggerTestUtil.toggleBreakpoint(project, file, zeroBasedLine)
        
        val listener = createSessionListener()
        val debugSession = startDebugSession(file.toNioPath(), listener)
        try {
            Assertions.assertTrue(
                XDebuggerTestUtil.waitFor(
                    listener.paused,
                    timeout.inWholeMilliseconds
                ),
                "Pause should be triggered within ${timeout.inWholeSeconds} seconds."
            )

            val suspendContext = debugSession.suspendContext as AutoHotKeySuspendContext
            Assertions.assertEquals(zeroBasedLine, suspendContext.activeExecutionStack.topFrame?.sourcePosition?.line)
        } finally {
            debugSession.stop()
        }
    }

    private val disposable = disposableFixture()
    @BeforeEach
    fun setUpLogging() {
        TestLoggerFactory.enableTraceLogging(
            disposable.get(),
            AutoHotKeyDebugProcess::class.java,
            DbgpClientImpl::class.java
        )
    }
    
    private val timeout = 60.seconds
    
    private val projectFixture = projectFixture()
    private val project: Project
        get() = projectFixture.get()
    
    private fun copyAndOpenFile(@Suppress("SameParameterValue") nameRelativeToTestData: String): VirtualFile {
        val resource = DebuggerTest::class.java.classLoader.getResource(nameRelativeToTestData)!!
        val originalFile = VfsUtil.findFileByURL(resource) ?: error("Cannot find file \"$resource\".")
        
        val basePath = Path(project.basePath!!)
        basePath.createDirectories()
        
        val baseDir = VfsUtil.findFile(basePath, /* refreshIfNeeded = */ true)
            ?: error("Cannot find base directory \"$basePath\".")
        return WriteCommandAction.runWriteCommandAction(
            project,
            Computable { originalFile.copy(this, baseDir, originalFile.name) }
        )
    } 
    
    private fun createConfiguration(scriptToRun: Path): AutoHotKeyFileRunConfiguration {
        val type = AutoHotKeyRunConfigurationType()
        val factory = type.configurationFactories.single()
        return AutoHotKeyFileRunConfiguration(project, factory, "Test Configuration").apply {
            filePath = scriptToRun
        }
    }
    
    private fun startDebugSession(scriptToRun: Path, listener: XDebugSessionListener): XDebugSession {
        val configuration = createConfiguration(scriptToRun)
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val runner = ProgramRunner.getRunner(executor.id, configuration) as? AutoHotKeyDebugProgramRunner
            ?: error("Expected AutoHotKeyDebugProgramRunner for configuration $configuration and executor ${executor.id}")
        val environment = ExecutionEnvironment(
            executor,
            runner,
            RunnerAndConfigurationSettingsImpl(RunManagerImpl.getInstanceImpl(project), configuration),
            project
        )
        val state = configuration.getState(executor, environment)
        return runBlocking { runner.createDebugSession(environment, state, listener) }
    }
    
    private fun createSessionListener() = object : XDebugSessionListener {
        val paused = Semaphore(0)
        override fun sessionPaused() {
            paused.release()
        }
    }
}
