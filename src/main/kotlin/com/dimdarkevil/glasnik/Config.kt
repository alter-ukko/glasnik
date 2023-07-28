package com.dimdarkevil.glasnik

import java.io.File

data class Config(
    var currentWorkspace: String = "",
    var editor: String = System.getenv("EDITOR") ?: "",
    var showCallTimes: Boolean = false,
    var outputDir: String = File(HOME, "glasnik-output").canonicalPath,
    var outputDest: OutputDest = OutputDest.console
)
