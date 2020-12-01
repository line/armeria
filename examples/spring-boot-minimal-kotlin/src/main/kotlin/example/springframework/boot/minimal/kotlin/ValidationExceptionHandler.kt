package example.springframework.boot.minimal.kotlin

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction
import java.time.Instant
import javax.validation.ValidationException

/**
 * A sample exception handler which handles a [ValidationException].
 */
class ValidationExceptionHandler : ExceptionHandlerFunction {

    private val mapper = jacksonObjectMapper()

    override fun handleException(ctx: ServiceRequestContext, req: HttpRequest, cause: Throwable): HttpResponse {
        return if (cause is ValidationException) {
            try {
                val status = HttpStatus.BAD_REQUEST
                HttpResponse.of(
                    status,
                    MediaType.JSON,
                    mapper.writeValueAsBytes(
                        ErrorResponse(
                            status.reasonPhrase(),
                            cause.message ?: "empty message",
                            req.path(),
                            status.code(),
                            Instant.now().toString()
                        )
                    )
                )
            } catch (e: JsonProcessingException) {
                HttpResponse.of(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8,
                    cause.message ?: "empty message"
                )
            }
        } else ExceptionHandlerFunction.fallthrough()
    }
}

/**
 * A sample HTTP response which is sent to a client when a [ValidationException] is raised.
 */
data class ErrorResponse(
    val error: String,
    val message: String,
    val path: String,
    val status: Int,
    val timestamp: String
)
