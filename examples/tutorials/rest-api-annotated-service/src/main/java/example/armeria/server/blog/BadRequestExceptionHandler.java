package example.armeria.server.blog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;

/**
 * Handles an {@link IllegalArgumentException} and returns an {@link HttpResponse} with
 * {@link HttpStatus#BAD_REQUEST}.
 */
public class BadRequestExceptionHandler implements ExceptionHandlerFunction {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
        if (cause instanceof IllegalArgumentException) {
            final String message = cause.getMessage();
            final ObjectNode objectNode = mapper.createObjectNode();
            objectNode.put("error", message);
            return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, objectNode);
        }
        return ExceptionHandlerFunction.fallthrough();
    }
}
