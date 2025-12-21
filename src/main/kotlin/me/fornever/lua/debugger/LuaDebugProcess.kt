package me.fornever.lua.debugger

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import java.util.concurrent.atomic.AtomicInteger

class LuaDebugProcess(session: XDebugSession) : XDebugProcess(session) {
    override fun getEditorsProvider(): XDebuggerEditorsProvider = LuaDebuggerEditorsProvider()   
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
