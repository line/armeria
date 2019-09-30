package example.springframework.boot.minimal

import java.util.Objects.requireNonNull

import java.time.Instant

import javax.validation.ValidationException

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction

/**
 * A sample exception handler which handles a [ValidationException].
 */
class ValidationExceptionHandler : ExceptionHandlerFunction {

    private val mapper = ObjectMapper()

    override fun handleException(ctx: ServiceRequestContext, req: HttpRequest, cause: Throwable): HttpResponse {
        if (cause is ValidationException) {
            try {
                val status = HttpStatus.BAD_REQUEST
                return HttpResponse.of(status, MediaType.JSON_UTF_8, mapper.writeValueAsBytes(
                        ErrorResponse(status.reasonPhrase(),
                                cause.message ?: "Invalid Parameter",
                                req.path(),
                                status.code(),
                                Instant.now().toString())))
            } catch (e: JsonProcessingException) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                        cause.message)
            }

        }
        return ExceptionHandlerFunction.fallthrough()
    }

    /**
     * A sample HTTP response which is sent to a client when a [ValidationException] is raised.
     */
    class ErrorResponse @JsonCreator
    internal constructor(@JsonProperty("error") error: String,
                         @JsonProperty("message") message: String,
                         @JsonProperty("path") path: String,
                         @param:JsonProperty("status") private val status: Int,
                         @JsonProperty("timestamp") timestamp: String) {
        private val error: String
        private val message: String
        private val path: String
        private val timestamp: String

        init {
            this.error = requireNonNull(error, "error")
            this.message = requireNonNull(message, "message")
            this.path = requireNonNull(path, "path")
            this.timestamp = requireNonNull(timestamp, "timestamp")
        }

        @JsonProperty
        fun error(): String {
            return error
        }

        @JsonProperty
        fun message(): String {
            return message
        }

        @JsonProperty
        fun path(): String {
            return path
        }

        @JsonProperty
        fun status(): Int {
            return status
        }

        @JsonProperty
        fun timestamp(): String {
            return timestamp
        }
    }
}
