package me.fornever.autohotkey.debugger

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.text.nullize
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.frame.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.fornever.autohotkey.debugger.dbgp.DbgpClient
import me.fornever.autohotkey.debugger.dbgp.DbgpPropertyInfo

class AutoHotKeyValue(
    private val scope: CoroutineScope,
    private val dbgp: DbgpClient,
    private val stackDepth: Int,
    private val property: DbgpPropertyInfo
) : XNamedValue(property.name) {
    
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(
            AllIcons.Nodes.Variable,
            property.type,
            property.value ?: "",
            property.children.isNotEmpty()
        )
    }

    override fun computeChildren(node: XCompositeNode) {
        if (property.children.isNotEmpty()) {
            val list = XValueChildrenList()
            for (child in property.children) {
                list.add(AutoHotKeyValue(scope, dbgp, stackDepth, child))
            }
            node.addChildren(list, true)
        }
    }

    override fun getModifier(): XValueModifier = object : XValueModifier() {
        override fun getInitialValueEditorText(): String? {
            return property.value 
        }

        override fun setValue(
            expression: XExpression,
            callback: XModificationCallback
        ) {
            scope.launch {
                try {
                    dbgp.setProperty(property, stackDepth, expression.expression)
                } catch (t: Throwable) {
                    if (t is ControlFlowException || t is CancellationException) throw t
                    logger.error(t)
                    callback.errorOccurred(
                        t.localizedMessage.nullize(true)
                            ?: t.message.nullize(true)
                            ?: DebuggerBundle.message("autohotkey.unknown.error")
                    )
                    return@launch
                }

                callback.valueModified()
            }
        }
    }
    
    companion object {
        private val logger = logger<AutoHotKeyValue>()
    }
}

class AutoHotKeyValueGroup(
    private val scope: CoroutineScope,
    private val dbgp: DbgpClient,
    private val stackDepth: Int,
    name: String,
    private val properties: List<DbgpPropertyInfo>): XValueGroup(name) {
    override fun computeChildren(node: XCompositeNode) {
        val list = XValueChildrenList()
        for (property in properties) {
            list.add(AutoHotKeyValue(scope, dbgp, stackDepth, property))
        }
        node.addChildren(list, true)
    }
}