package com.dimdarkevil.glasnik

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.util.*
import kotlin.system.exitProcess

object Glasnik {
    private val configDir = File(HOME, ".glasnik")
    private val om = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val cmds = Command.values().map { it.name }.toSet()
    private val cmdsNeedingArg = setOf(Command.USE, Command.ADD, Command.DELETE)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val config = loadConfig()
            if (args.size == 1 && (args[0] == "-h" || args[0] == "--help")) throw RuntimeException(help())
            val firstarg = if (args.isEmpty()) "STATUS" else args[0].uppercase()
            val hasCommand = (cmds.contains(firstarg))
            val command = if (hasCommand) Command.valueOf(firstarg) else Command.CALL
            if (command in cmdsNeedingArg && args.size < 2) throw RuntimeException("${command} requires another argument")
            when (command) {
                Command.STATUS -> status(config)
                Command.USE -> use(config, args[1])
                Command.ADD -> add(config, args[1])
                Command.DELETE -> delete(config, args[1])
                Command.EDIT -> edit(config, if (args.size > 1) args[1] else null)
                Command.UPDATE -> update(config)
                Command.LIST -> list(config)
                Command.CALLS -> calls(config)
                Command.CLEAR -> clear(config)
                Command.HELP -> println(help())
                Command.CALL -> {
                    val realArgs = if (hasCommand) args.drop(1) else args.toList()
                    val bodyFilename = if (realArgs.size > 1) realArgs[1] else null
                    call(config, realArgs[0], bodyFilename)
                }
            }
        } catch (e: Exception) {
            println(e.message)
            exitProcess(1)
        }
    }

    private fun status(config: Config) {
        val workspace = config.currentWorkspace.ifEmpty { "*no workspace selected*" }
        val vars = try {
            loadWorkspaceConfig(config.currentWorkspace).currentVars.let {
                it.ifEmpty { "*no vars selected*" }
            }
        } catch (e: Exception) {
            "*no vars selected*"
        }
        val msg = """
            workspace: $workspace
            vars file: $vars
        """.trimIndent()
        println(msg)
    }

    private fun use(config: Config, arg: String) {
        val (workspace, argVars) = when {
            arg.startsWith(".") -> Pair(config.currentWorkspace, arg.substring(1))
            arg.contains(".") -> Pair(arg.substringBeforeLast("."), arg.substringAfterLast("."))
            else -> Pair(arg, "")
        }
        if (workspace.isEmpty()) throw RuntimeException("No current workspace")
        val workspaceConfig = loadWorkspaceConfig(workspace)
        val vars = (argVars.ifEmpty { workspaceConfig.currentVars }).ifEmpty { getFirstVarsInWorkspace(workspace) }
        if (vars.isEmpty()) throw RuntimeException("No current vars in workspace $workspace")
        val varsFile = getVarsFile(workspace, vars)
        if (!varsFile.exists()) throw RuntimeException("Vars $vars does not exist in workspace $workspace")
        val clearExtracts =  (config.currentWorkspace == workspace && workspaceConfig.currentVars != vars)
        config.currentWorkspace = workspace
        saveConfig(config)
        workspaceConfig.currentVars = vars
        if (clearExtracts) workspaceConfig.extractedVars.clear()
        saveWorkspaceConfig(workspace, workspaceConfig)
    }

    private fun add(config: Config, arg: String) {
        if (!arg.contains(".")) throw RuntimeException("No default vars specified")
        val workspace = if (arg.startsWith(".")) config.currentWorkspace else arg.substringBeforeLast(".")
        // check for validity of args
        if (workspace.contains(".")) throw RuntimeException("Workspace name can't contain dot")
        if (workspace.isEmpty()) throw RuntimeException("No current workspace")
        val vars = arg.substringAfterLast(".")
        if (vars.isEmpty()) throw RuntimeException("No default vars specified")
        if (vars == workspace) throw RuntimeException("vars can't be named the same thing as the workspace")

        val workspaceDir = File(configDir, workspace)
        if (!workspaceDir.exists()) {
            val bodiesDir = File(workspaceDir, "bodies")
            bodiesDir.mkdirs()
            saveWorkspaceConfig(workspace, WorkspaceConfig())
        }
        val workspaceConfig = loadWorkspaceConfig(workspace)
        val callsFile = getCallsFile(workspace)
        if (!callsFile.exists()) saveWorkspaceCalls(workspace, mapOf())
        val calls = loadWorkspaceCalls(workspace)
        val varsFile = getVarsFile(workspace, vars)
        if (varsFile.exists()) throw RuntimeException("vars $vars already exists")
        val varMap = getVarsInCalls(calls).associateWith { "" }
        saveVars(workspace, vars, varMap)
        if (config.currentWorkspace.isEmpty()) {
            config.currentWorkspace = workspace
            saveConfig(config)
        }
        if (workspaceConfig.currentVars.isEmpty()) {
            workspaceConfig.currentVars = vars
            saveWorkspaceConfig(workspace, workspaceConfig)
        }
    }

    private fun delete(config: Config, arg: String) {
        val (workspace, vars) = when {
            arg.startsWith(".") -> Pair(config.currentWorkspace, arg.substring(1))
            arg.contains(".") -> Pair(arg.substringBeforeLast("."), arg.substringAfterLast("."))
            else -> Pair(arg, "")
        }
        if (workspace.isEmpty()) throw RuntimeException("No current workspace")
        val workspaceDir = File(configDir, workspace)
        if (vars.isEmpty()) {
            // just deleting a whole workspace
            workspaceDir.deleteRecursively()
            workspaceDir.delete()
            if (config.currentWorkspace == workspace) {
                config.currentWorkspace = ""
                saveConfig(config)
            }
        } else {
            // just deleting args
            val workspaceConfig = loadWorkspaceConfig(workspace)
            val varsFile = getVarsFile(workspace, vars)
            if (!varsFile.exists()) throw RuntimeException("Vars $vars doesn't exist in workspace $workspace")
            varsFile.delete()
            if (workspaceConfig.currentVars == vars) {
                workspaceConfig.currentVars = ""
                saveWorkspaceConfig(workspace, workspaceConfig)
            }
        }
    }

    private fun edit(config: Config, arg: String?) {
        val editor = config.editor.ifEmpty { System.getenv("EDITOR") ?: "" }
        if (editor.isEmpty()) throw RuntimeException("No editor set in ~/.glasnik/glasnik.yml and no EDITOR env var")
        val (workspace, vars) = when {
            arg == null -> Pair(config.currentWorkspace, "")
            arg.startsWith(".") -> Pair(config.currentWorkspace, arg.substring(1))
            arg.contains(".") -> Pair(arg.substringBeforeLast("."), arg.substringAfterLast("."))
            else -> Pair(arg, "")
        }
        if (workspace.isEmpty()) throw RuntimeException("No current workspace")
        if (vars.isEmpty()) {
            // editing calls for workspace
            val workspaceCallsFile = getCallsFile(workspace)
            execBash(editor, listOf(workspaceCallsFile.canonicalPath), true)
        } else {
            // editing vars
            val varsFile = getVarsFile(workspace, vars)
            execBash(editor, listOf(varsFile.canonicalPath), true)
        }
    }

    private fun update(config: Config) {
        if (config.currentWorkspace.isEmpty()) throw RuntimeException("No current workspace")
        val usedVars = getVarsInCalls(loadWorkspaceCalls(config.currentWorkspace))
        getVarNamesInWorkspace(config.currentWorkspace).forEach { vars ->
            val varsMap = loadVars(config.currentWorkspace, vars)
            var changed = false
            usedVars.forEach {
                if (varsMap[it] == null) {
                    varsMap[it] = ""
                    changed = true
                }
            }
            if (changed) {
                println("adding vars to ${config.currentWorkspace}.${vars}")
                saveVars(config.currentWorkspace, vars, varsMap)
            }
        }
    }

    private fun list(config: Config) {
        println("workspaces:")
        configDir.listFiles { f -> f.isDirectory }?.filter { getWorkspaceConfigFile(it.name).exists() }?.forEach { ws ->
            val workspace = if (ws.name == config.currentWorkspace) "*${ws.name}" else ws.name
            println("${GREEN}$workspace${RESET}")
        } ?: println("No workspaces exist")
    }

    private fun calls(config: Config) {
        if (config.currentWorkspace.isEmpty()) throw RuntimeException("No current workspace")
        println("calls in ${config.currentWorkspace}:")
        loadWorkspaceCalls(config.currentWorkspace).forEach { (callName, call) ->
            println("${YELLOW}$callName${RESET} -> ${call.method} ${call.url}")
        }
    }

    private fun clear(config: Config) {
        if (config.currentWorkspace.isEmpty()) throw RuntimeException("No current workspace")
        loadWorkspaceConfig(config.currentWorkspace).let { wsConfig ->
            wsConfig.extractedVars.clear()
            saveWorkspaceConfig(config.currentWorkspace, wsConfig)
            println("cleared extracted vars in workspace ${config.currentWorkspace}")
        }
    }

    private fun call(config: Config, callName: String, bodyFilename: String?) {
        if (config.currentWorkspace.isEmpty()) throw RuntimeException("No current workspace")
        val workspaceConfig = loadWorkspaceConfig(config.currentWorkspace)
        if (workspaceConfig.currentVars.isEmpty()) throw RuntimeException("No current vars in workspace ${config.currentWorkspace}")
        val bodiesDir = File(File(configDir, config.currentWorkspace), "bodies")
        val vars = loadVars(config.currentWorkspace, workspaceConfig.currentVars)
            .plus(workspaceConfig.extractedVars)
        val calls = loadWorkspaceCalls(config.currentWorkspace)
        val call = calls[callName] ?: throw RuntimeException("No call named ${callName} in workspace ${config.currentWorkspace}")
        val url = call.url.substituteVars(vars)
        val headers = call.headers?.mapValues { it.value.substituteVars(vars) }
        println("-=-= REQUEST (${config.currentWorkspace}.${workspaceConfig.currentVars})")
        println("${GREEN}${url}${RESET}")
        headers?.forEach { (name, value) ->
            println("${BOLD}${name}${RESET}: $value")
        }
        println()
        println("-=-= RESPONSE (${config.currentWorkspace}.${workspaceConfig.currentVars})")
        val client = OkHttpClient()
        val response = when (call.method) {
            HttpMethod.GET -> doGet(client, url, headers)
            HttpMethod.POST -> {
                val body:RequestBody =
                    when {
                        call.multipartFiles != null -> { // multipart/*
                            if ( ! call.contentType.startsWith( "multipart/") ) {
                                error("If call.multipartFiles is set, contentType must be multipart/*")
                            }
                            MultipartBody.Builder().apply {
                                call.multipartFiles.forEach { multipartFile ->
                                    val file = File(bodiesDir, multipartFile.path)
                                    val contentType:String? = multipartFile.contentType
                                        ?: Files.probeContentType(file.toPath())
                                    val body =
                                        if ( multipartFile.substituteVars ) {
                                            file
                                                .readText(Charsets.UTF_8)
                                                .substituteVars(vars)
                                                .toRequestBody(contentType?.toMediaTypeOrNull())
                                        } else {
                                            file.asRequestBody(contentType?.toMediaTypeOrNull())
                                        }
                                    addFormDataPart(
                                        name = multipartFile.name,
                                        filename = multipartFile.filename ?: file.name,
                                        body = body
                                    )
                                }
                                setType(call.contentType.toMediaType())
                            }.build()
                        }
                        call.form != null -> { // application/x-www-form-urlencoded
                            if ( call.contentType != "application/x-www-form-urlencoded" ) {
                                error("If call.form is set, contentType must be application/x-www-form-urlencoded")
                            }
                            FormBody.Builder().apply {
                                call.form.forEach { (k,v) ->
                                    add(k,v.substituteVars(vars))
                                }
                            }.build()
                        }
                        bodyFilename != null -> {
                            val file = File(bodiesDir, bodyFilename)
                            if ( call.bodySubstituteVars ) {
                                file
                                    .readText(Charsets.UTF_8)
                                    .substituteVars(vars)
                                    .toRequestBody(call.contentType.toMediaTypeOrNull())
                            } else {
                                file.asRequestBody(call.contentType.toMediaTypeOrNull())
                            }
                        }
                        else -> {
                            call.body
                                ?.let {
                                    if ( call.bodySubstituteVars ) {
                                        it.substituteVars(vars)
                                    } else {
                                        it
                                    }
                                }
                                ?.toRequestBody(call.contentType.toMediaTypeOrNull())
                        }
                    } ?: throw RuntimeException("POST with no body specified")
                doPost(client, url, body, headers)
            }
        }
        println("${BOLD}${YELLOW}${response.code}${RESET}")
        val responseHeaders = response.headers
        responseHeaders.forEach { (name, value) ->
            println("${BOLD}${name}${RESET}: $value")
        }
        val responseBody = response.body?.string()
        responseBody?.let { rb ->
            if (response.headers.size > 0) println()
            println(rb)
        }
        var changedWorkspaceConfig = false
        call.extracts?.forEach { extract ->
            //println("extract from ${extract.from} ${extract.to} ${extract.value}")
            when (extract.from) {
                ResponseExtractType.JSON_BODY -> {
                    responseBody?.let { body ->
                        //println("body is $body")
                        jacksonObjectMapper().readTree(body).let { obj ->
                            //println("obj is $obj")
                            obj[extract.value]?.textValue()?.let {
                                workspaceConfig.extractedVars[extract.to] = it
                                changedWorkspaceConfig = true
                            }
                        }
                    }
                }
                ResponseExtractType.HEADER -> {
                    responseHeaders.find { it.first == extract.value }?.let {
                        workspaceConfig.extractedVars[extract.to] = it.second
                        changedWorkspaceConfig = true
                    }
                }
            }
        }
        if (changedWorkspaceConfig) saveWorkspaceConfig(config.currentWorkspace, workspaceConfig)
    }

    private fun doGet(client: OkHttpClient, url: String, headers: Map<String,String>?) : Response {
        val req = Request.Builder().url(url).apply {
            headers?.forEach { (name, value) -> addHeader(name, value) }
        }.build()
        return client.newCall(req).execute()
    }

    private fun doPost(client: OkHttpClient, url: String, body: RequestBody, headers: Map<String,String>?) : Response {
        val req = Request.Builder()
            .url(url)
            .post(body)
            .apply {
                headers?.forEach { (name, value) -> addHeader(name, value) }
            }.build()
        return client.newCall(req).execute()
    }

    private fun loadConfig() = getConfigFile().let { configFile ->
            if (configFile.exists()) {
                om.readValue(configFile)
            } else {
                Config().apply { saveConfig(this) }
            }
        }

    private fun saveConfig(config: Config) = om.writeValue(getConfigFile(), config)

    private fun getConfigFile() = File(configDir, "glasnik.yml").apply {
            if (!this.parentFile.exists()) this.parentFile.mkdirs()
        }

    private fun loadWorkspaceConfig(workspace: String) : WorkspaceConfig {
        val workspaceConfigFile = getWorkspaceConfigFile(workspace)
        if (!workspaceConfigFile.exists()) throw RuntimeException("$workspace has no config file")
        return om.readValue(workspaceConfigFile)
    }

    private fun saveWorkspaceConfig(workspace: String, config: WorkspaceConfig) {
        val workspaceConfigFile = getWorkspaceConfigFile(workspace)
        om.writeValue(workspaceConfigFile, config)
    }

    private fun getWorkspaceConfigFile(workspace: String) : File {
        if (workspace.isEmpty()) throw RuntimeException("No workspace specified")
        val workspaceDir = File(configDir, workspace)
        if (!workspaceDir.exists()) throw RuntimeException("Workspace $workspace does not exist")
        return File(workspaceDir, "${workspace}.yml")
    }

    private fun loadWorkspaceCalls(workspace: String) : MutableMap<String,CallConfig> {
        val callsFile = getCallsFile(workspace)
        return om.readValue(callsFile)
    }

    private fun saveWorkspaceCalls(workspace: String, calls: Map<String,CallConfig>) {
        val callsFile = getCallsFile(workspace)
        om.writeValue(callsFile, calls)
    }

    private fun getCallsFile(workspace: String) : File {
        val workspaceDir = File(configDir, workspace)
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        return File(workspaceDir, "calls.yml")
    }

    private fun getVarsInCalls(calls: Map<String,CallConfig>) : Set<String> {
        val re = Regex("\\{(.*?)}")
        val extractVars = calls.values.mapNotNull { it.extracts }.flatMap { extracts ->
            extracts.map { it.to }
        }.toSet()
        val urlVars =  calls.values.map {it.url}.flatMap { url ->
            re.findAll(url).map { mr -> mr.groupValues[1] }
        }
        val bodyVars = calls.values.mapNotNull { it.body }.flatMap { body ->
            re.findAll(body).map { mr -> mr.groupValues[1] }
        }
        val headerVars = calls.values.flatMap { it.headers?.values ?: emptyList() }.flatMap { headerVal ->
            re.findAll(headerVal).map { mr -> mr.groupValues[1] }
        }
        return (urlVars + bodyVars + headerVars).filter { !extractVars.contains(it) }.toSet()
    }

    private fun loadVars(workspace: String, varsName: String) : MutableMap<String,String> {
        val varsFile = getVarsFile(workspace, varsName)
        if (!varsFile.exists()) throw RuntimeException("Vars $varsName doesn't exist in workspace $workspace")
        val props = FileReader(varsFile).use { rdr ->
            Properties().apply { load(rdr) }
        }
        return props.map { (key, value) -> "$key" to "$value" }.toMap().toMutableMap()
    }

    private fun saveVars(workspace: String, varsName: String, vars: Map<String,String>) {
        val varsFile = getVarsFile(workspace, varsName)
        Properties().let { props ->
            vars.forEach { (key, value) ->  props[key] = value }
            FileWriter(varsFile).use { wtr ->
                props.store(wtr, "")
            }
        }
    }

    private fun getVarsFile(workspace: String, varsName: String) : File {
        val workspaceDir = File(configDir, workspace)
        return File(workspaceDir, "${varsName}.properties")
    }

    private fun getVarNamesInWorkspace(workspace: String) : List<String> {
        val workspaceFile = File(configDir, workspace)
        val varsFiles = workspaceFile.listFiles { f -> f.extension == "properties" && f.nameWithoutExtension != workspace }
            ?: arrayOf()
        if (varsFiles.isEmpty()) throw RuntimeException("No vars files in workspace $workspace")
        return varsFiles.map { it.nameWithoutExtension }
    }

    private fun getFirstVarsInWorkspace(workspace: String) : String {
        return getVarNamesInWorkspace(workspace).firstOrNull() ?: throw RuntimeException("No vars in workspace $workspace")
    }

    private fun execBash(cmd: String, args: List<String> = listOf(), wait: Boolean = false) : Int	{
        val dir = File(System.getProperty("user.dir"))
        val pb = if (args.isNotEmpty()) {
            println("running $cmd with args $args in dir $dir")
            ProcessBuilder(cmd, *args.toTypedArray()).directory(dir).inheritIO()
        } else {
            ProcessBuilder(cmd).directory(dir).inheritIO()
        }
        val proc = pb.start()
        return if (wait) {
            proc.waitFor()
        } else 0
    }

    fun String.substituteVars(vars: Map<String,String>) : String {
        var s = this
        vars.forEach { (key, value) ->
            s = s.replace("{$key}", value)
        }
        return s
    }

    private fun help() : String {
        return """
			|glasnik version ${Version.version}
			|usage:
			|${BOLD}${YELLOW}glasnik [status]${RESET} - show status (current workspace and vars)
            |${BOLD}${YELLOW}glasnik use {workspace_name}${RESET} - switch to a workspace (default vars}
            |${BOLD}${YELLOW}glasnik use {workspace_name}.{vars_name}${RESET} - switch to a workspace and vars
            |${BOLD}${YELLOW}glasnik use .{vars_name}${RESET} - switch to vars in the current workspace
            |${BOLD}${YELLOW}glasnik add {workspace_name}.{vars_name}${RESET} - add a new workspace and/or vars file
            |${BOLD}${YELLOW}glasnik add .{vars_name}${RESET} - add a new or vars file in the current workspace
            |${BOLD}${YELLOW}glasnik delete {workspace_name}${RESET} - delete a workspace
            |${BOLD}${YELLOW}glasnik delete {workspace_name}.{vars_name}${RESET} - delete a vars file in the specified workspace
            |${BOLD}${YELLOW}glasnik delete .{vars_name}${RESET} - delete a vars file in the current workspace
            |${BOLD}${YELLOW}glasnik edit${RESET} - edit calls in the current workspace
            |${BOLD}${YELLOW}glasnik edit {workspace_name}.{vars_name}${RESET} - edit vars in the specified workspace
            |${BOLD}${YELLOW}glasnik edit .{vars_name}${RESET} - edit vars in the current workspace
            |${BOLD}${YELLOW}glasnik update${RESET} - update vars in the current workspace to include anything in calls
            |${BOLD}${YELLOW}glasnik list${RESET} - list workspaces
            |${BOLD}${YELLOW}glasnik calls${RESET} - list calls in the current workspace
            |${BOLD}${YELLOW}glasnik clear${RESET} - clear extracted vars in the current workspace
			|${BOLD}${YELLOW}glasnik [call] {call_name} [{body_filename}]${RESET} - issue a call in the current workspace
            |${BOLD}${YELLOW}glasnik help|-h|--help${RESET} - show this message
		""".trimMargin("|")
    }
}
