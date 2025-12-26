package me.fornever.autohotkey.debugger

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.text.nullize
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.impl.XSourcePositionImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.fornever.autohotkey.debugger.dbgp.DbgpClient
import me.fornever.autohotkey.debugger.dbgp.DbgpStackInfo
import java.util.concurrent.CancellationException

class AutoHotKeySuspendContext(private val stack: AutoHotKeyExecutionStack) : XSuspendContext() {
    override fun getActiveExecutionStack(): XExecutionStack = stack
}

class AutoHotKeyExecutionStack(
    private val coroutineScope: CoroutineScope,
    private val dbgpClient: DbgpClient,
    private val topStackElement: DbgpStackInfo,
    private val maxDepth: Int
) : XExecutionStack("AutoHotKey") {
    override fun getTopFrame(): XStackFrame = AutoHotKeyStackFrame(coroutineScope, dbgpClient, topStackElement, 0)

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        coroutineScope.launch { 
            for (d in firstFrameIndex until maxDepth) {
                val info = dbgpClient.getStackInfo(d)
                val isLast = d == maxDepth - 1
                container.addStackFrames(listOf(AutoHotKeyStackFrame(coroutineScope, dbgpClient, info, d)), isLast)
            }
        }
    }
}

class AutoHotKeyStackFrame(
    private val coroutineScope: CoroutineScope,
    private val dbgpClient: DbgpClient,
    private val info: DbgpStackInfo,
    private val depth: Int
) : XStackFrame() {
    
    override fun getEvaluator(): XDebuggerEvaluator? {
        return super.getEvaluator() // TODO
    }

    override fun getSourcePosition(): XSourcePosition? {
        val file = VfsUtil.findFile(info.file, false) ?: return null
        return XSourcePositionImpl.create(file, info.oneBasedLineNumber - 1)
    }

    override fun computeChildren(node: XCompositeNode) {
        coroutineScope.launch { 
            try {
                val list = XValueChildrenList()
                
                val contexts = dbgpClient.getAllContexts()
                for (context in contexts) {
                    val properties = dbgpClient.getProperties(depth, context.id)
                    
                    val isDefault = context.id == 0
                    if (isDefault) {
                        for (property in properties) {
                            list.add(AutoHotKeyValue(property))
                        }
                    } else {
                        val group = AutoHotKeyValueGroup(context.name, properties)
                        list.addBottomGroup(group)
                    }
                }
                
                val isLast = true
                node.addChildren(list, isLast)
            } catch (e: Exception) {
                if (e is ControlFlowException || e is CancellationException) throw e
                logger.error(e)
                node.setErrorMessage((e.localizedMessage ?: e.message)?.nullize() ?: DebuggerBundle.message("autohotkey.unknown.error"))
            }
        }
    }
    
    companion object {
        private val logger = logger<AutoHotKeyStackFrame>()
    }
}
