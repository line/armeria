package example.springframework.boot.minimal;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import javax.validation.ValidationException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;

/**
 * A sample exception handler which handles a {@link ValidationException}.
 */
public class ValidationExceptionHandler implements ExceptionHandlerFunction {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
        if (cause instanceof ValidationException) {
            try {
                final HttpStatus status = HttpStatus.BAD_REQUEST;
                return HttpResponse.of(status, MediaType.JSON_UTF_8, mapper.writeValueAsBytes(
                        new ErrorResponse(status.reasonPhrase(),
                                          cause.getMessage(),
                                          req.path(),
                                          status.code(),
                                          Instant.now().toString())));
            } catch (JsonProcessingException e) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                       cause.getMessage());
            }
        }
        return ExceptionHandlerFunction.fallthrough();
    }

    /**
     * A sample HTTP response which is sent to a client when a {@link ValidationException} is raised.
     */
    public static class ErrorResponse {
        private final String error;
        private final String message;
        private final String path;
        private final int status;
        private final String timestamp;

        @JsonCreator
        ErrorResponse(@JsonProperty("error") String error,
                      @JsonProperty("message") String message,
                      @JsonProperty("path") String path,
                      @JsonProperty("status") int status,
                      @JsonProperty("timestamp") String timestamp) {
            this.error = requireNonNull(error, "error");
            this.message = requireNonNull(message, "message");
            this.path = requireNonNull(path, "path");
            this.status = status;
            this.timestamp = requireNonNull(timestamp, "timestamp");
        }

        @JsonProperty
        public String error() {
            return error;
        }

        @JsonProperty
        public String message() {
            return message;
        }

        @JsonProperty
        public String path() {
            return path;
        }

        @JsonProperty
        public int status() {
            return status;
        }

        @JsonProperty
        public String timestamp() {
            return timestamp;
        }
    }
}
