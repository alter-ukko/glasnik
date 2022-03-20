package com.dimdarkevil.glasnik

data class CallConfig(
    val url: String = "",
    val method: HttpMethod = HttpMethod.GET,
    val contentType: String = "application/json",
    val headers: Map<String,String>? = null,
    val body: String? = null,
    val extracts: List<CallExtract>? = null,
)

data class CallExtract(
    val from: ResponseExtractType = ResponseExtractType.JSON_BODY,
    val to: String = "",
    val value: String = "",
)

enum class ResponseExtractType {
    HEADER,
    JSON_BODY,
}
