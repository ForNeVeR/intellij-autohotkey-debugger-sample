package me.fornever.lua.debugger

import com.intellij.execution.CantRunException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.*
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.psi.PsiElement
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.text.nullize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element
import org.jetbrains.annotations.NonNls
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

class LuaRunConfigurationProducer : LazyRunConfigurationProducer<LuaFileRunConfiguration>() {
    
    override fun getConfigurationFactory(): ConfigurationFactory {
        val type = ConfigurationType.CONFIGURATION_TYPE_EP.findExtensionOrFail(LuaRunConfigurationType::class.java)
        return type.configurationFactories.single()
    }

    override fun setupConfigurationFromContext(
        configuration: LuaFileRunConfiguration,
        context: ConfigurationContext,
        element: Ref<PsiElement?>
    ): Boolean {
        val elem = context.psiLocation
        val file = elem?.containingFile?.virtualFile
        val path = file?.toNioPath()
        if (path?.extension != "lua") return false
        
        configuration.filePath = path
        configuration.name = path.fileName.pathString
        return true
    }

    override fun isConfigurationFromContext(
        configuration: LuaFileRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val file = context.psiLocation?.containingFile ?: return false
        val currentFile = file.virtualFile
        return currentFile != null && currentFile.toNioPath() == configuration.filePath
    }
}

class LuaRunConfigurationType : ConfigurationTypeBase(
    "me.fornever.lua",
    "Lua",
    "Run Lua scripts with debugging support",
    AllIcons.RunConfigurations.Application
) {    
    init {
        addFactory(LuaRunConfigurationFactory(this))
    }
}

class LuaRunConfigurationFactory(type: LuaRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): @NonNls String = "me.fornever.lua"
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        LuaFileRunConfiguration(project, this, "Lua Run Configuration Template")
}

class LuaFileRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : LocatableConfigurationBase<Element>(project, factory, name) {
    
    var filePath: Path? = null
    
    override fun getState(
        executor: Executor,
        environment: ExecutionEnvironment
    ): LuaFileRunProfileState = LuaFileRunProfileState(
        environment,
        filePath ?: throw CantRunException("File path is not set.")
    )

    override fun getConfigurationEditor(): SettingsEditor<LuaFileRunConfiguration> =
        LuaRunConfigurationEditor(filePath?.pathString)

    override fun readExternal(element: Element) {
        super.readExternal(element)
        val filePathString = element.getAttributeValue("filePath")?.nullize() ?: return
        filePath = filePathString.toNioPathOrNull()
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        filePath?.pathString?.let { element.setAttribute("filePath", it) }
    }
}

class LuaRunConfigurationEditor(
    private var filePathString: String?
) : SettingsEditor<LuaFileRunConfiguration>() {
    
    override fun resetEditorFrom(configuration: LuaFileRunConfiguration) {
        filePathString = configuration.filePath?.pathString
    }

    override fun applyEditorTo(configuration: LuaFileRunConfiguration) {
        configuration.filePath = filePathString?.toNioPathOrNull()
    }

    override fun createEditor(): JComponent = panel {
        row {
            textField()
                .bindText(
                    { filePathString.orEmpty() },
                    { filePathString = it.nullize() }
                )
                .label("File path:")
        }
    }
}

class LuaFileRunProfileState(
    environment: ExecutionEnvironment,
    val filePath: Path
) : CommandLineState(environment) {
    
    companion object {
        private val defaultLuaInterpreterPath = run {
            if (!SystemInfo.isWindows) {
                return@run null
            }
        
            val programFiles = System.getenv("ProgramFiles(x86)") ?: System.getenv("ProgramFiles") ?: return@run null
            Path.of(programFiles, "Lua", "5.1", "lua.exe")
        }
        
        private fun findLuaInterpreter(): Path? =
            PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("lua")?.toPath()
                ?: defaultLuaInterpreterPath.takeIf { it?.exists() == true }
        
        private val logger = logger<LuaFileRunProfileState>()

        private fun luaEscape(text: String): String =
            text.replace("\"", "\\\"").replace("\\", "\\\\")
        
        private val mobDebugScript: Path by lazy {
            ThreadingAssertions.assertBackgroundThread()
            
            val resource = LuaDebugProgramRunner::class.java.classLoader.getResourceAsStream("lua/mobdebug.lua")
                ?: error("Resource not found: lua/mobdebug.lua")
            resource.use {
                val path = FileUtil.createTempFile("mobdebug", ".lua", /*deleteOnExit = */true).toPath()
                path.outputStream().use {
                    resource.copyTo(it)    
                }
                
                path
            }
        }
    }
    
    private fun startProcess(arguments: List<String>): ProcessHandler {
        val luaInterpreter = findLuaInterpreter() ?: throw CantRunException("Lua interpreter is not found.")
        val commandLine = PtyCommandLine()
            .withConsoleMode(false)
            .withWorkingDirectory(filePath.parent)
            .withExePath(luaInterpreter.pathString)
            .withParameters(arguments + filePath.pathString)

        return KillableColoredProcessHandler(commandLine)
    }
    
    override fun startProcess(): ProcessHandler =
        startProcess(emptyList())
    
    suspend fun startDebugProcess(port: Int): ProcessHandler {
        val debuggerScript = withContext(Dispatchers.IO) { mobDebugScript }
        val command = "dofile(\"${luaEscape(debuggerScript.pathString)}\").start(\"127.0.0.1\", $port)"
        logger.info("Will execute command in debuggee process: $command")
        return withContext(Dispatchers.IO) { startProcess(listOf("-e", command)) }
    }
}
