package com.dimdarkevil.glasnik

data class WorkspaceConfig(
    var currentVars: String  = "",
    val extractedVars: MutableMap<String,String> = mutableMapOf(),
)
