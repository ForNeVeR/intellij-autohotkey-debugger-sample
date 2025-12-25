package me.fornever.autohotkey.debugger

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.XSourcePositionImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.fornever.autohotkey.debugger.dbgp.DbgpClient
import me.fornever.autohotkey.debugger.dbgp.DbgpStackInfo

class AutoHotKeySuspendContext(private val stack: AutoHotKeyExecutionStack) : XSuspendContext() {
    override fun getActiveExecutionStack(): XExecutionStack = stack
}

class AutoHotKeyExecutionStack(
    val coroutineScope: CoroutineScope,
    val dbgpClient: DbgpClient,
    val topStackElement: DbgpStackInfo,
    val depth: Int
) : XExecutionStack("AutoHotKey") {
    override fun getTopFrame(): XStackFrame = AutoHotKeyStackFrame(topStackElement)

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        coroutineScope.launch { 
            for (d in firstFrameIndex until depth) {
                val info = dbgpClient.getStackInfo(d)
                val isLast = d == depth - 1
                container.addStackFrames(listOf(AutoHotKeyStackFrame(info)), isLast)
            }
        }
    }
}

class AutoHotKeyStackFrame(private val info: DbgpStackInfo) : XStackFrame() {
    
    override fun getEvaluator(): XDebuggerEvaluator? {
        return super.getEvaluator() // TODO
    }

    override fun getSourcePosition(): XSourcePosition? {
        val file = VfsUtil.findFile(info.file, false) ?: return null
        return XSourcePositionImpl.create(file, info.oneBasedLineNumber - 1)
    }

    override fun computeChildren(node: XCompositeNode) {
        // TODO: Calculate variables 
    }
}
