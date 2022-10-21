package com.dimdarkevil.glasnik

data class Config(
    var currentWorkspace: String = "",
    var editor: String = System.getenv("EDITOR") ?: "",
    var showCallTimes: Boolean = false,
)
