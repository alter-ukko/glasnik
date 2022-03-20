package com.dimdarkevil.glasnik

const val RESET = "\u001B[0m"

const val BOLD = "\u001B[1m"
const val DIM = "\u001B[2m"
const val UNDERLINE = "\u001B[4m"
const val BLINK = "\u001B[5m"
const val REVERSE = "\u001B[7m"
const val INVISIBLE = "\u001B[8m"

const val BLACK = "\u001B[30m"
const val RED = "\u001B[31m"
const val GREEN = "\u001B[32m"
const val YELLOW = "\u001B[33m"
const val BLUE = "\u001B[34m"
const val PURPLE = "\u001B[35m"
const val CYAN = "\u001B[36m"
const val WHITE = "\u001B[37m"
const val BLACK_BG = "\u001B[40m"
const val RED_BG = "\u001B[41m"
const val GREEN_BG = "\u001B[42m"
const val YELLOW_BG = "\u001B[43m"
const val BLUE_BG = "\u001B[44m"
const val PURPLE_BG = "\u001B[45m"
const val CYAN_BG = "\u001B[46m"
const val WHITE_BG = "\u001B[47m"

val HOME = System.getProperty("user.home") ?: throw RuntimeException("Can't get 'user.home' system property")
