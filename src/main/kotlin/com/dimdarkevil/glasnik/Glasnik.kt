package com.dimdarkevil.glasnik

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jayway.jsonpath.JsonPath
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.system.exitProcess

object Glasnik {
    private val configDir = File(HOME, ".glasnik")
    private val om = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val cmds = Command.values().map { it.name }.toSet()
    private val cmdsNeedingArg = setOf(Command.USE, Command.ADD, Command.DELETE)
    private val cmdsNeedingTwoArgs = setOf(Command.SET)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val config = loadConfig()
            if (args.size == 1 && (args[0] == "-h" || args[0] == "--help")) throw RuntimeException(help())
            val firstarg = if (args.isEmpty()) "STATUS" else args[0].uppercase()
            val hasCommand = (cmds.contains(firstarg))
            val command = if (hasCommand) Command.valueOf(firstarg) else Command.CALL
            if (command in cmdsNeedingTwoArgs && args.size < 3) throw RuntimeException("${command} requires two arguments after the command")
            if (command in cmdsNeedingArg && args.size < 2) throw RuntimeException("${command} requires an argument after the command")
            when (command) {
                Command.STATUS -> status(config)
                Command.CONFIG -> editConfig(config)
                Command.USE -> use(config, args[1])
                Command.ADD -> add(config, args[1])
                Command.DELETE -> delete(config, args[1])
                Command.EDIT -> edit(config, if (args.size > 1) args[1] else null)
                Command.SET -> set(config, args[1], args[2])
                Command.UPDATE -> update(config)
                Command.LIST -> list(config)
                Command.CALLS -> calls(config)
                Command.VARS -> vars(config)
                Command.CLEAR -> clear(config)
                Command.HELP -> println(help())
                Command.CALL, Command.E, Command.S -> {
                    val realCommand = if (hasCommand) Command.valueOf(firstarg) else Command.CALL
                    val realArgs = if (hasCommand) args.drop(1) else args.toList()
                    val bodyFilename = if (realArgs.size > 1) realArgs[1] else null
                    call(config, realCommand, realArgs[0], bodyFilename)
                }
                Command.OUTPUT -> setOutput(config, if (args.size > 1) args[1] else null)
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
        val outputDir = if (config.outputDest == OutputDest.file) " ${config.outputDir}" else ""
        val msg = """
            workspace: $workspace
            vars file: $vars
            output destination: ${config.outputDest}${outputDir}
        """.trimIndent()
        println(msg)
    }

    private fun editConfig(config: Config) {
        getConfigFile().let { configFile ->
            if (configFile.exists()) {
                val editor = config.editor.ifEmpty { System.getenv("EDITOR") ?: "" }
                if (editor.isEmpty()) throw RuntimeException("No editor set in ~/.glasnik/glasnik.yml and no EDITOR env var")
                execBash(editor, listOf(configFile.canonicalPath), true)
            } else {
                println("config file ${configFile.canonicalPath} does not exist")
            }
        }
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

    private fun set(config: Config, varName: String, value: String) {
        if (config.currentWorkspace.isEmpty()) throw RuntimeException("No current workspace")
        val workspaceConfig = loadWorkspaceConfig(config.currentWorkspace)
        val vars = loadVars(config.currentWorkspace, workspaceConfig.currentVars)
        val extractableVars = extractableVars(config.currentWorkspace)
        if (extractableVars.contains(varName)) {
            workspaceConfig.extractedVars[varName] = value
            saveWorkspaceConfig(config.currentWorkspace, workspaceConfig)
        } else {
            vars[varName] = value
            saveVars(config.currentWorkspace, workspaceConfig.currentVars, vars)
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
            val workspaceConfig = loadWorkspaceConfig(ws.name)
            ws.listFiles { f -> f.extension == "properties" }?.forEach { vf ->
                val varFile = if (vf.nameWithoutExtension == workspaceConfig.currentVars) "*${vf.nameWithoutExtension}" else vf.nameWithoutExtension
                println("  $varFile")
            }
        } ?: println("No workspaces exist")
    }

    private fun calls(config: Config) {
        if (config.currentWorkspace.isEmpty()) throw RuntimeException("No current workspace")
        println("${BOLD}calls in ${config.currentWorkspace}:${RESET}")
        loadWorkspaceCalls(config.currentWorkspace).forEach { (callName, call) ->
            if (call.extracts.isNullOrEmpty()) {
                println("${YELLOW}$callName${RESET} -> ${call.method} ${call.url}")
            } else {
                println("${YELLOW}$callName${RESET} -> ${call.method} ${call.url} [extracts ${GREEN}${call.extracts.map { it.to }.joinToString()}${RESET}]")
            }
        }
    }

    private fun vars(config: Config) {
        if (config.currentWorkspace.isEmpty()) throw RuntimeException("No current workspace")
        loadWorkspaceConfig(config.currentWorkspace).let { workspaceConfig ->
            if (workspaceConfig.currentVars.isEmpty())
                throw RuntimeException("No current vars in workspace ${config.currentWorkspace}")
            println("${BOLD}vars in ${config.currentWorkspace}.${workspaceConfig.currentVars}:${RESET}")
            val vars = loadVars(config.currentWorkspace, workspaceConfig.currentVars)
            vars.keys.sorted().forEach { varKey ->
                println("${YELLOW}${varKey}${RESET} -> ${vars[varKey]}")
            }
            println("${BOLD}extracted vars in ${config.currentWorkspace}.${workspaceConfig.currentVars}:${RESET}")
            workspaceConfig.extractedVars.keys.sorted().forEach { varKey ->
                println("${YELLOW}${varKey}${RESET} -> ${workspaceConfig.extractedVars[varKey]}")
            }
            extractableVars(config.currentWorkspace).filter { workspaceConfig.extractedVars[it] == null }.let { unextracted ->
                if (unextracted.isNotEmpty()) {
                    println("${BOLD}unextracted vars in ${config.currentWorkspace}.${workspaceConfig.currentVars}:${RESET}")
                    unextracted.forEach { varKey ->
                        println("${YELLOW}${varKey}${RESET}")
                    }
                }
            }
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

    private fun call(config: Config, actualCommand: Command, callName: String, bodyFilename: String?) {
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
        val (response, dt) = stopwatch { when (call.method) {
            HttpMethod.GET -> doGet(client, url, headers)
            HttpMethod.PATCH,
            HttpMethod.PUT,
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
                // could be a post, a put or a patch
                doPost(client, call.method, url, body, headers)
            }
        }}

        httpResponseCodes[response.code]?.let {
            println("${BOLD}${YELLOW}${response.code}${RESET} ($it)")
        } ?: println("${BOLD}${YELLOW}${response.code}${RESET}")

        val responseHeaders = response.headers
        responseHeaders.forEach { (name, value) ->
            println("${BOLD}${name}${RESET}: $value")
        }
        val contentType = response.headers["Content-Type"] ?: "text/plain"
        val responseBody = response.body?.string()?.let {
            if (contentType.endsWith("/json", true)) {
                val om = ObjectMapper().registerKotlinModule()
                    .configure(SerializationFeature.INDENT_OUTPUT, true)
                try {
                    om.readTree(it).toPrettyString()
                } catch (e: Exception) {
                    it
                }
            } else it
        }
        responseBody?.let { rb ->
            if (config.outputDest == OutputDest.file || actualCommand == Command.S || actualCommand == Command.E) {
                if (response.headers.size > 0) println()
                val outFile = writeResponseBodyToFile(callName, rb, contentType, config)
                println("wrote response body to: file://${outFile.canonicalPath}")
                if (actualCommand == Command.E) {
                    val editor = config.editor.ifEmpty { System.getenv("EDITOR") ?: "" }
                    if (editor.isEmpty()) throw RuntimeException("No editor set in ~/.glasnik/glasnik.yml and no EDITOR env var")
                    execBash(editor, listOf(outFile.canonicalPath), true)
                }
            } else {
                if (response.headers.size > 0) println()
                println(rb)
            }
        }
        var changedWorkspaceConfig = false
        call.extracts?.forEach { extract ->
            //println("extract from ${extract.from} ${extract.to} ${extract.value}")
            when (extract.from) {
                ResponseExtractType.JSON_BODY -> {
                    responseBody?.let { body ->
                        JsonPath.read<String>(body, extract.value).let {
                            workspaceConfig.extractedVars[extract.to] = it
                            changedWorkspaceConfig = true
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
        if (config.showCallTimes) {
            println("${CYAN}call took:${RESET} ${dt.toDurationStr()}")
        }

        if (changedWorkspaceConfig) saveWorkspaceConfig(config.currentWorkspace, workspaceConfig)
    }

    private fun writeResponseBodyToFile(callName: String, body: String, contentType: String, config: Config) : File {
        val ext = if (contentType == "text/plain") "txt" else contentType.split(";").first().split("/").last()
        val outDir = config.outputDir.fileReplacingTilde()
        val outFile = File(outDir, "${config.currentWorkspace}/${callName}_${zonedNowString()}.${ext}")
        outFile.parentFile.mkdirs()
        outFile.writeText(body)
        return outFile
    }

    private fun doGet(client: OkHttpClient, url: String, headers: Map<String,String>?) : Response {
        val req = Request.Builder().url(url).apply {
            headers?.forEach { (name, value) -> addHeader(name, value) }
        }.build()
        return client.newCall(req).execute()
    }

    private fun doPost(client: OkHttpClient, method: HttpMethod, url: String, body: RequestBody, headers: Map<String,String>?) : Response {
        val req = Request.Builder()
            .url(url).let {
                when (method) {
                    HttpMethod.POST -> it.post(body)
                    HttpMethod.PATCH -> it.patch(body)
                    HttpMethod.PUT -> it.put(body)
                    else -> throw RuntimeException("method ${method} isn't a post-like method")
                }
            }
            .apply {
                headers?.forEach { (name, value) -> addHeader(name, value) }
            }.build()
        return client.newCall(req).execute()
    }

    private fun setOutput(config: Config, outputDestStr: String?) {
        outputDestStr?.let {
            val outDest = try {
                OutputDest.valueOf(it.lowercase())
            } catch (e: Exception) {
                error("invalid output dest '${outputDestStr}'. Must be one of ${OutputDest.values()}")
            }
            config.outputDest = outDest
            saveConfig(config)
            val outDestFileLoc = if (outDest == OutputDest.file) " (${config.outputDir})" else ""
            println("response body output will now go to ${outDest}${outDestFileLoc}")
        } ?: run {
            val outDestFileLoc = if (config.outputDest == OutputDest.file) " (${config.outputDir})" else ""
            println("response body output goes to ${config.outputDest}${outDestFileLoc}")
        }
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

    private fun extractableVars(workspace: String): List<String> {
        val calls = loadWorkspaceCalls(workspace)
        return calls.values.mapNotNull { it.extracts }.flatMap { extracts ->
            extracts.map { it.to }
        }.toSet().sorted()
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
			|${BOLD}${YELLOW}glasnik config${RESET} - edit the global glasnik configuration file
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
            |${BOLD}${YELLOW}glasnik set {var_name} {value}${RESET} - set the value of a var in the current workspace
            |${BOLD}${YELLOW}glasnik update${RESET} - update vars in the current workspace to include anything in calls
            |${BOLD}${YELLOW}glasnik list${RESET} - list workspaces
            |${BOLD}${YELLOW}glasnik calls${RESET} - list calls in the current workspace
            |${BOLD}${YELLOW}glasnik vars${RESET} - list vars in the current workspace
            |${BOLD}${YELLOW}glasnik clear${RESET} - clear extracted vars in the current workspace
			|${BOLD}${YELLOW}glasnik [call] {call_name} [{body_filename}]${RESET} - issue a call in the current workspace
			|${BOLD}${YELLOW}glasnik e {call_name} [{body_filename}]${RESET} - issue a call in the current workspace and edit the response body
			|${BOLD}${YELLOW}glasnik s {call_name} [{body_filename}]${RESET} - issue a call in the current workspace and save response body
            |${BOLD}${YELLOW}glasnik output console|file|none${RESET} - send response body output to a different destination
            |${BOLD}${YELLOW}glasnik help|-h|--help${RESET} - show this message
		""".trimMargin("|")
    }

    fun Long.toDurationStr() : String {
        val durations = listOf(
            Pair("days", 86400000),
            Pair("hours", 3600000),
            Pair("minutes", 60000),
            Pair("seconds", 1000),
            Pair("ms", 1)
        )
        var ms = this
        val sb = StringBuilder()
        for (p in durations) {
            var label = p.first
            val dur = p.second
            if (ms >= dur) {
                val n = (ms / dur)
                ms -= (dur * n)
                if (sb.isNotEmpty()) sb.append(", ")
                if (n == 1L) label = label.substring(0, label.length-1)
                sb.append("$n $label")
            }
        }
        return sb.toString()
    }

    fun <T>stopwatch(fn: () -> T) : Pair<T,Long> {
        val st = System.currentTimeMillis()
        return fn().let {
            Pair(it, (System.currentTimeMillis() - st))
        }
    }

    fun zonedNowString(zoneId: ZoneId = ZoneId.of("UTC")) =
        ZonedDateTime.now(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .replace(Regex("[\\:\\-\\.]"), "_")

    fun String.fileReplacingTilde() : File = if (this.startsWith("~/")) {
        File(HOME, this.substring(2))
    } else {
        File(this)
    }
}
