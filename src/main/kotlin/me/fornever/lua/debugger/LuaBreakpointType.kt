package me.fornever.lua.debugger

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType

class LuaBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>("ahk", DebuggerBundle.message("lua.breakpoint.type.display.name")) {

    override fun canPutAt(
        file: VirtualFile,
        line: Int,
        project: Project
    ): Boolean {
        return file.extension == "ahk"
    }

    override fun createBreakpointProperties(
        file: VirtualFile,
        line: Int
    ): XBreakpointProperties<*>? = null
}