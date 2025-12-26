package me.fornever.autohotkey.debugger

import com.intellij.icons.AllIcons
import com.intellij.xdebugger.frame.*
import me.fornever.autohotkey.debugger.dbgp.DbgpPropertyInfo

class AutoHotKeyValue(private val property: DbgpPropertyInfo) : XNamedValue(property.name) {
    
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(AllIcons.Nodes.Variable, property.type, property.name, false)
    }

    override fun computeChildren(node: XCompositeNode) {
        if (property.children.isNotEmpty()) {
            val list = XValueChildrenList()
            for (child in property.children) {
                list.add(AutoHotKeyValue(child))
            }
            node.addChildren(list, true)
        }
    }
}

class AutoHotKeyValueGroup(name: String, private val properties: List<DbgpPropertyInfo>): XValueGroup(name) {
    override fun computeChildren(node: XCompositeNode) {
        val list = XValueChildrenList()
        for (property in properties) {
            list.add(AutoHotKeyValue(property))
        }
        node.addChildren(list, true)
    }
}