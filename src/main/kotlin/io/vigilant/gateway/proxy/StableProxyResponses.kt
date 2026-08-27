package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType

/** Builds one stable JSON proxy error without source-dependent details. */
internal fun stableProxyError(
    status: HttpStatus,
    errorCode: String,
): HttpResponse = HttpResponse.of(status, MediaType.JSON, """{"error":"$errorCode"}""")
