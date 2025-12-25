package me.fornever.autohotkey.debugger

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import fleet.util.logging.logger
import java.util.concurrent.atomic.AtomicInteger

class AutoHotKeyDebugProcess(
    private val session: XDebugSession,
    private val debuggeeHandler: ProcessHandler,
    private val debugger: AutoHotKeyDebugger
) : XDebugProcess(session), Disposable.Default {
    
    init {
        Disposer.register(this, debugger)
        debugger.connectToSession(session)
    }

    override fun stop() {
        Disposer.dispose(this)
    }
    
    private val editorsProvider by lazy { AutoHotKeyDebuggerEditorsProvider() }
    private val breakpointHandlers by lazy { arrayOf(AutoHotKeyBreakpointHandler(session, debugger)) }
    
    override fun doGetProcessHandler(): ProcessHandler = debuggeeHandler
    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider
    override fun getBreakpointHandlers(): Array<out XBreakpointHandler<*>> = breakpointHandlers

    override fun createConsole(): ExecutionConsole {
        return TerminalExecutionConsole(session.project, processHandler).also {
            processHandler.startNotify()
        }
    }

    override fun sessionInitialized() {
        logger.info("Debug session initialized.")
        debugger.launchInOrder { debugger.initializeAndResume() }
        super.sessionInitialized()
    }

    companion object {
        private val logger = logger<AutoHotKeyDebugProcess>()
    }
}

class AutoHotKeyDebuggerEditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    companion object {
        private var documentId = AtomicInteger(0)
    }
    
    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode
    ): Document {
        val id = documentId.getAndIncrement()
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(
                "debugger$id.ahk",
                PlainTextFileType.INSTANCE,
                expression.expression,
                LocalTimeCounter.currentTime(),
                true
            )
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        return document
    }
}

class AutoHotKeyBreakpointHandler(
    private val session: XDebugSession,
    private val debugger: AutoHotKeyDebugger
) : XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(AutoHotKeyBreakpointType::class.java) {
    
    companion object {
        private val logger = logger<AutoHotKeyBreakpointHandler>()
    }
    
    private fun validateBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>): Boolean {
        val sourcePosition = breakpoint.sourcePosition
        if (sourcePosition == null || !sourcePosition.file.exists() || !sourcePosition.file.isValid) {
            session.setBreakpointInvalid(breakpoint, DebuggerBundle.message("autohotkey.breakpoint.invalid"))
            logger.warn("Invalid breakpoint: $breakpoint: file doesn't exist or is invalid")
            return false
        }

        val lineNumber: Int = breakpoint.line
        if (lineNumber < 0) {
            session.setBreakpointInvalid(breakpoint, DebuggerBundle.message("autohotkey.breakpoint.invalid"))
            logger.warn("Invalid breakpoint $breakpoint: line $lineNumber")
            return false
        }
        
        return true
    }
    
    override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
        if (!validateBreakpoint(breakpoint)) return
        debugger.launchInOrder { debugger.setBreakpoint(breakpoint) }
    }

    override fun unregisterBreakpoint(
        breakpoint: XLineBreakpoint<XBreakpointProperties<*>>,
        temporary: Boolean
    ) {
        debugger.launchInOrder { debugger.removeBreakpoint(breakpoint) }
    }
}
