package me.fornever.lua.debugger

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class LuaDebugProcess(
    private val session: XDebugSession,
    private val debuggeeHandler: ProcessHandler,
    debugger: LuaDebugger
) : XDebugProcess(session), Disposable.Default {
    
    init {
        Disposer.register(this, debugger)
    }

    override fun stop() {
        Disposer.dispose(this)
    }
    
    private val editorsProvider by lazy { LuaDebuggerEditorsProvider() }
    private val breakpointHandlers by lazy { arrayOf(LuaBreakpointHandler(session, debugger, debugger.scope)) }
    
    override fun doGetProcessHandler(): ProcessHandler = debuggeeHandler
    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider
    override fun getBreakpointHandlers(): Array<out XBreakpointHandler<*>> = breakpointHandlers
}

class LuaDebuggerEditorsProvider : XDebuggerEditorsProvider() {
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
                "debugger$id.lua",
                PlainTextFileType.INSTANCE,
                expression.expression,
                LocalTimeCounter.currentTime(),
                true
            )
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        return document
    }
}

class LuaBreakpointHandler(
    private val session: XDebugSession,
    private val debugger: DbgpDebugger,
    private val coroutineScope: CoroutineScope
) : XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(LuaBreakpointType::class.java) {

    companion object {
        private val logger = logger<LuaBreakpointHandler>()
    }
    
    private fun validateBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>): Boolean {
        val sourcePosition = breakpoint.sourcePosition
        if (sourcePosition == null || !sourcePosition.file.exists() || !sourcePosition.file.isValid) {
            session.setBreakpointInvalid(breakpoint, DebuggerBundle.message("lua.breakpoint.invalid"))
            logger.warn("Invalid breakpoint: $breakpoint: file doesn't exist or is invalid")
            return false
        }

        val lineNumber: Int = breakpoint.line
        if (lineNumber < 0) {
            session.setBreakpointInvalid(breakpoint, DebuggerBundle.message("lua.breakpoint.invalid"))
            logger.warn("Invalid breakpoint $breakpoint: line $lineNumber")
            return false
        }
        
        return true
    }
    
    override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
        if (!validateBreakpoint(breakpoint)) return
        coroutineScope.launch { debugger.setBreakpoint(breakpoint) }
    }

    override fun unregisterBreakpoint(
        breakpoint: XLineBreakpoint<XBreakpointProperties<*>>,
        temporary: Boolean
    ) {
        coroutineScope.launch { debugger.removeBreakpoint(breakpoint) }
    }
}
