package me.fornever.autohotkey.debugger

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.nullize
import com.intellij.xdebugger.XSourcePosition
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
            try {
                for (d in firstFrameIndex until maxDepth) {
                    val info = dbgpClient.getStackInfo(d)
                    val isLast = d == maxDepth - 1
                    container.addStackFrames(listOf(AutoHotKeyStackFrame(coroutineScope, dbgpClient, info, d)), isLast)
                }
            } catch (e: Throwable) {
                if (e is ControlFlowException || e is CancellationException) throw e
                thisLogger().error(e)
                container.errorOccurred(
                    e.localizedMessage.nullize(true)
                        ?: e.message.nullize(true)
                        ?: DebuggerBundle.message("general.unknown-error")
                )
            }
        }
    }
}

class AutoHotKeyStackFrame(
    private val scope: CoroutineScope,
    private val dbgp: DbgpClient,
    private val info: DbgpStackInfo,
    private val depth: Int
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? {
        val file = VfsUtil.findFile(info.file, false) ?: return null
        return XSourcePositionImpl.create(file, info.oneBasedLineNumber - 1)
    }

    override fun customizePresentation(component: ColoredTextContainer) {
        super.customizePresentation(component)
        info.symbolName?.let { component.append(", $it", SimpleTextAttributes.REGULAR_ATTRIBUTES) }
    }

    override fun computeChildren(node: XCompositeNode) {
        scope.launch {
            try {
                val list = XValueChildrenList()

                val contexts = dbgp.getAllContexts()
                for (context in contexts) {
                    val properties = dbgp.getProperties(depth, context.id)

                    val isDefault = context.id == 0
                    if (isDefault) {
                        for (property in properties) {
                            list.add(AutoHotKeyValue(scope, dbgp, depth, property))
                        }
                    } else {
                        val group = AutoHotKeyValueGroup(scope, dbgp, depth, context.name, properties)
                        list.addBottomGroup(group)
                    }
                }

                val isLast = true
                node.addChildren(list, isLast)
            } catch (e: Exception) {
                if (e is ControlFlowException || e is CancellationException) throw e
                logger.error(e)
                node.setErrorMessage((e.localizedMessage ?: e.message)?.nullize() ?: DebuggerBundle.message("general.unknown-error"))
            }
        }
    }

    companion object {
        private val logger = logger<AutoHotKeyStackFrame>()
    }
}
