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

val httpResponseCodes = mapOf(
    100 to "Continue",
    101 to "Switching Protocols",
    200 to "OK",
    201 to "Created",
    202 to "Accepted",
    203 to "Non-Authoritative Information",
    204 to "No Content",
    205 to "Reset Content",
    206 to "Partial Content",
    300 to "Multiple Choices",
    301 to "Moved Permanently",
    302 to "Found",
    303 to "See Other",
    304 to "Not Modified",
    305 to "Use Proxy",
    307 to "Temporary Redirect",
    400 to "Bad Request",
    401 to "Unauthorized",
    402 to "Payment Required",
    403 to "Forbidden",
    404 to "Not Found",
    405 to "Method Not Allowed",
    406 to "Not Acceptable",
    407 to "Proxy Authentication Required",
    408 to "Request Timeout",
    409 to "Conflict",
    410 to "Gone",
    411 to "Length Required",
    412 to "Precondition Failed",
    413 to "Request Entity Too Large",
    414 to "Request-URI Too Long",
    415 to "Unsupported Media Type",
    416 to "Requested Range Not Satisfiable",
    417 to "Expectation Failed",
    500 to "Internal Server Error",
    501 to "Not Implemented",
    502 to "Bad Gateway",
    503 to "Service Unavailable",
    504 to "Gateway Timeout",
    505 to "HTTP Version Not Supported",
)
