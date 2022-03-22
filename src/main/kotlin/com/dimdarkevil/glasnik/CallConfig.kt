package com.dimdarkevil.glasnik

data class CallConfig(
    val url: String = "",
    val method: HttpMethod = HttpMethod.GET,
    val contentType: String = "application/json",
    val headers: Map<String,String>? = null,
    val body: String? = null,
    /**
     * If true, substitute vars in request body, which could be [body]
     * or body file passed as command line arg.
     * If substituting vars on body file, it is read as UTF-8.
     */
    val bodySubstituteVars: Boolean = true,
    val extracts: List<CallExtract>? = null,
    /** If set, send as multipart.
     * [contentType] must be `multipart/?`, e.g.: `multipart/form-data`. */
    val multipartFiles: List<MultipartFile>? = null,
    /** If set, send as application/x-www-form-urlencoded */
    val form: Map<String,String>? = null,
)

/**
 * File for [CallConfig.multipartFiles].
 */
data class MultipartFile(
    /** Path relative to `.glasnik/{workspace}/bodies/` */
    val path:String,
    /** Multipart form-data name */
    val name:String,
    /** Multipart form-data filename, if null use filename */
    val filename:String? = null,
    /** If true, read file as UTF-8 and substitute vars. */
    val substituteVars: Boolean = false,
    /** content type, if null probe file */
    val contentType: String? = null,
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
