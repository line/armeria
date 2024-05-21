package example.springframework.boot.minimal.kotlin

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction
import jakarta.validation.ValidationException
import java.time.Instant

/**
 * A sample exception handler which handles a [ValidationException].
 */
class ValidationExceptionHandler : ExceptionHandlerFunction {
    override fun handleException(
        ctx: ServiceRequestContext,
        req: HttpRequest,
        cause: Throwable,
    ): HttpResponse {
        return if (cause is ValidationException) {
            val status = HttpStatus.BAD_REQUEST
            HttpResponse.ofJson(
                status,
                ErrorResponse(
                    status.reasonPhrase(),
                    cause.message ?: "empty message",
                    req.path(),
                    status.code(),
                    Instant.now().toString(),
                ),
            )
        } else {
            ExceptionHandlerFunction.fallthrough()
        }
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
    val timestamp: String,
)
