package com.linecorp.armeria.server.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Decorates an {@link HttpService} to handle JSON-RPC 2.0 requests.
 * It parses incoming JSON-RPC requests, validates them, maps them to internal HTTP requests
 * based on the method name, calls the delegate service, and converts the delegate's response
 * back into a JSON-RPC response. It also handles JSON-RPC notifications and error mapping.
 */
public class JsonRpcServiceDecorator extends SimpleDecoratingHttpService {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcServiceDecorator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a new instance that handles JSON-RPC requests for all paths.
     * @param delegate The {@link HttpService} to delegate the actual method execution to.
     */
    public JsonRpcServiceDecorator(HttpService delegate) {
        super(delegate);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpService delegate = (HttpService) unwrap();

        logger.trace("Path {} processing as JSON-RPC.", ctx.path());

        return HttpResponse.of(req.aggregate().thenCompose(aggregatedReq -> {
            try {
                final JsonRpcRequest rpcRequest = parseAndValidateRequest(aggregatedReq);


                if (rpcRequest.isNotification()) {
                    return handleNotification(ctx, delegate, rpcRequest);
                } else {
                    return handleRegularCall(ctx, delegate, rpcRequest);
                }
            } catch (JsonRpcRequestParseException e) {
                logger.warn("Failed to parse or validate JSON-RPC request: {}", aggregatedReq.contentUtf8(), e);
                return createErrorHttpResponseFuture(e.getErrorResponse(), e.getRequestId());
            } catch (Exception e) {
                logger.error("Unexpected error during initial processing of JSON-RPC request: {}", aggregatedReq.contentUtf8(), e);
                final JsonRpcError error = JsonRpcError.internalError("Unexpected server error during request parsing");
                final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, null);
                return createErrorHttpResponseFuture(rpcResponse, null);
            }
        }));
    }

    /**
     * Parses the JSON request body into a {@link JsonRpcRequest} and performs basic validation.
     * @throws JsonRpcRequestParseException if parsing or validation fails.
     */
    private JsonRpcRequest parseAndValidateRequest(AggregatedHttpRequest aggregatedReq) throws JsonRpcRequestParseException {
        final String requestBody = aggregatedReq.contentUtf8();
        logger.trace("Received JSON-RPC request body: {}", requestBody);
        try {
            final JsonRpcRequest rpcRequest = objectMapper.readValue(requestBody, JsonRpcRequest.class);

            if (!"2.0".equals(rpcRequest.jsonrpc())) {
                throw new IllegalArgumentException("Invalid JSON-RPC version: " + rpcRequest.jsonrpc());
            }
            if (rpcRequest.method() == null || rpcRequest.method().isEmpty()) {
                throw new IllegalArgumentException("JSON-RPC request 'method' is missing or empty.");
            }
            if (!rpcRequest.hasObjectParams() && !rpcRequest.hasArrayParams() && rpcRequest.params() != null) {
                throw new IllegalArgumentException("JSON-RPC request 'params' must be an object or an array.");
            }

            logger.debug("Parsed JSON-RPC request: method={}, id={}", rpcRequest.method(), rpcRequest.id());
            return rpcRequest;
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse JSON-RPC request body: {}", requestBody, e);
            final JsonRpcError error = JsonRpcError.parseError("Invalid JSON received: " + e.getMessage());
            final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, null); // ID is unknown
            throw new JsonRpcRequestParseException(e, rpcResponse, null);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid JSON-RPC request structure: {}", requestBody, e);
            final JsonRpcError error = JsonRpcError.invalidRequest(e.getMessage());
            final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, null);
            throw new JsonRpcRequestParseException(e, rpcResponse, null);
        }
    }

    /**
     * Handles JSON-RPC notification requests by preparing and initiating the delegate call asynchronously,
     * logging any potential errors during preparation/initiation, and returning an immediate NO_CONTENT response.
     * The actual response from the delegate is ignored.
     */
    private CompletableFuture<HttpResponse> handleNotification(ServiceRequestContext ctx, HttpService delegate, JsonRpcRequest rpcRequest) {
        final String methodName = rpcRequest.method();
        final JsonNode params = rpcRequest.params();
        final Object requestId = rpcRequest.id();

        logger.debug("Processing notification request (method: {}), initiating delegate call asynchronously.", methodName);
        try {

            // Prepare and serve the delegate, but ignore the returned Future
            final CompletableFuture<AggregatedHttpResponse> delegateResponseFuture = prepareAndServeDelegate(ctx, delegate, methodName, params, requestId);

            // Log completion/failure using whenComplete for observability
            delegateResponseFuture.whenComplete((response, throwable) -> {
                 if (throwable != null) {
                     logger.warn("Async delegate call for notification method '{}' (id: {}) failed: {}",
                                 methodName, requestId, throwable.getMessage(), throwable);
                 } else {
                      logger.trace("Async delegate call for notification method '{}' (id: {}) completed with status: {}",
                                  methodName, requestId, response != null ? response.status() : "N/A");
                 }
             });

        } catch (JsonProcessingException e) {
             logger.error("Failed to serialize JSON-RPC params for notification method '{}' (id: {}): {}",
                          methodName, requestId, e.getMessage(), e);
        } catch (Exception e) {
             logger.error("Unexpected error preparing or initiating delegate call for notification method '{}' (id: {}): {}",
                           methodName, requestId, e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(HttpResponse.of(HttpStatus.NO_CONTENT));
    }

    /**
     * Handles regular JSON-RPC requests (non-notifications) by preparing and calling the delegate,
     * then processing the delegate's response.
     */
    private CompletableFuture<HttpResponse> handleRegularCall(ServiceRequestContext ctx, HttpService delegate, JsonRpcRequest rpcRequest) {
        final String methodName = rpcRequest.method();
        final JsonNode params = rpcRequest.params();
        final Object requestId = rpcRequest.id();

        try {
            // Prepare, serve the delegate, and get the response future
            final CompletableFuture<AggregatedHttpResponse> delegateResponseFuture = prepareAndServeDelegate(ctx, delegate, methodName, params, requestId);

            // Attach the response processing handler
            return delegateResponseFuture.handle((delegateResponse, throwable) ->
                processDelegateResponse(delegateResponse, throwable, methodName, requestId)
            );

        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize JSON-RPC params for method '{}' (id: {}): {}",
                         methodName, requestId, e.getMessage(), e);
            final JsonRpcError error = JsonRpcError.internalError("Failed to serialize parameters for internal request");
            final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, requestId);
            return createErrorHttpResponseFuture(rpcResponse, requestId);

        } catch (Exception e) {
            logger.error("Unexpected error preparing or initiating delegate call for method '{}' (id: {}): {}",
                          methodName, requestId, e.getMessage(), e);
            final JsonRpcError error = JsonRpcError.internalError("Unexpected server error before calling delegate");
            final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, requestId);
            return createErrorHttpResponseFuture(rpcResponse, requestId);
        }
    }

    /**
     * Prepares the internal HTTP request, calls the delegate service, and returns the aggregated response future.
     * This method contains the common logic for both regular calls and notifications.
     * @throws Exception if parameter serialization or delegate execution fails.
     */
    private CompletableFuture<AggregatedHttpResponse> prepareAndServeDelegate(
            ServiceRequestContext ctx, HttpService delegate, String methodName, @Nullable JsonNode params, @Nullable Object requestId)
            throws Exception {

        final String internalPath = "/".equals(ctx.path()) ? '/' + methodName : ctx.path() + '/' + methodName;
        final String paramsJson = (params == null || params.isNull()) ? "null" : objectMapper.writeValueAsString(params);


        final HttpRequest internalRequest = HttpRequest.of(
                HttpMethod.POST,
                internalPath,
                MediaType.JSON_UTF_8,
                paramsJson
        );

        final String logId = requestId != null ? requestId.toString() : "notification";
        logger.debug("Delegating JSON-RPC method '{}' (id: {}) to internal path: {} with body: {}",
                     methodName, logId, internalPath, paramsJson);

        try (SafeCloseable ignored = ctx.push()) {
             return delegate.serve(ctx, internalRequest).aggregate();
        }
    }

    /**
     * Processes the response (or throwable) from the delegate service call and converts it
     * into the final JSON-RPC {@link HttpResponse}.
     */
    private HttpResponse processDelegateResponse(@Nullable AggregatedHttpResponse delegateResponse, @Nullable Throwable throwable,
                                                  String methodName, @Nullable Object requestId) {
        try {
           if (throwable != null) {
                logger.warn("Error executing delegate service or aggregating response for JSON-RPC method '{}' (id: {}): {}",
                        methodName, requestId, throwable.getMessage(), throwable);
                final JsonRpcError error = mapThrowableToJsonRpcError(throwable, methodName, requestId);
                final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, requestId);
                return createSyncHttpResponse(rpcResponse, requestId);
            }

            // If throwable is null, delegateResponse must be non-null (due to CompletableFuture contract)
            assert delegateResponse != null;

            final HttpStatus status = delegateResponse.status();
            final String content = delegateResponse.contentUtf8();
            logger.debug("Received delegate response for JSON-RPC method '{}' (id: {}): status={}, content={}",
                    methodName, requestId, status, content);

            JsonRpcResponse rpcResponse;
            if (status.isSuccess()) {
                try {
                    final JsonNode resultNode = objectMapper.readTree(content);
                    rpcResponse = JsonRpcResponse.ofSuccess(resultNode, requestId);
                } catch (JsonProcessingException e) {
                    logger.warn("Failed to parse delegate response body as JSON for method '{}' (id: {}). Body: {}. Error: {}",
                            methodName, requestId, content, e.getMessage());
                    final JsonRpcError error = JsonRpcError.internalError(
                            "Delegate returned successful status but non-JSON body: " + content);
                    rpcResponse = JsonRpcResponse.ofError(error, requestId);
                }
           } else {
                final JsonRpcError error = mapHttpResponseToJsonRpcError(delegateResponse, methodName, requestId);
                rpcResponse = JsonRpcResponse.ofError(error, requestId);
            }

            return createSyncHttpResponse(rpcResponse, requestId);

        } catch (Exception e) {
             logger.error("Unexpected error handling delegate response for method '{}' (id: {}): {}",
                     methodName, requestId, e.getMessage(), e);
             final JsonRpcError error = JsonRpcError.internalError("Unexpected server error handling delegate response");
             final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, requestId);
             return createSyncHttpResponse(rpcResponse, requestId);
       }
    }


    /**
     * Creates the final {@link HttpResponse} containing the serialized JSON-RPC response.
     * This method is used for successful responses or errors that occur *after* the initial parsing phase.
     * It always returns HTTP OK status, with the JSON-RPC error details in the body if applicable.
     */
    private HttpResponse createSyncHttpResponse(JsonRpcResponse rpcResponse, @Nullable Object requestId) {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                   objectMapper.writeValueAsString(rpcResponse));
        } catch (JsonProcessingException jpe) {
            logger.error("Failed to serialize final JSON-RPC response for request id {}", requestId, jpe);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                   "Internal Server Error: Failed to serialize JSON-RPC response");
        }
    }

    /**
     * Creates a {@link CompletableFuture<HttpResponse>} containing the serialized JSON-RPC error response.
     * This is used for errors detected during the initial request parsing/validation phase.
     */
    private CompletableFuture<HttpResponse> createErrorHttpResponseFuture(JsonRpcResponse rpcResponse, @Nullable Object requestId) {
         try {
            final HttpResponse errorHttpResponse = HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                 objectMapper.writeValueAsString(rpcResponse));
            return CompletableFuture.completedFuture(errorHttpResponse);
        } catch (JsonProcessingException jpe) {
            logger.error("Failed to serialize JSON-RPC error response for request id {}", requestId, jpe);
            return CompletableFuture.completedFuture(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    /**
     * Maps a {@link Throwable} (potentially from the delegate execution) to a {@link JsonRpcError}.
     */
    private JsonRpcError mapThrowableToJsonRpcError(Throwable throwable, String methodName, @Nullable Object requestId) {
        Throwable cause = throwable;
         while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

         logger.warn("Mapping throwable for method '{}' (id: {}): {}", methodName, requestId, cause.getMessage(), cause);

         if (cause instanceof JsonRpcRequestParseException) {
             return JsonRpcError.parseError("Invalid JSON received: " + cause.getMessage());
         }
         if (cause instanceof JsonProcessingException) {
            return JsonRpcError.internalError("Failed to parse successful delegate response as JSON: " + cause.getMessage());
         }
         if (cause instanceof IllegalArgumentException) {
             return JsonRpcError.invalidRequest("Invalid argument encountered: " + cause.getMessage());
         }

        return JsonRpcError.internalError("Internal server error during delegate processing: " + cause.getMessage());
    }

    /**
     * Maps an error {@link AggregatedHttpResponse} from the delegate service to a {@link JsonRpcError}.
     */
    private JsonRpcError mapHttpResponseToJsonRpcError(AggregatedHttpResponse aggResp, String methodName, @Nullable Object requestId) {
        final HttpStatus status = aggResp.status();
        final String content = aggResp.contentUtf8();

        logger.warn("Mapping HTTP error response for method '{}' (id: {}): status={}, content='{}'",
                    methodName, requestId, status, content);

        if (status == HttpStatus.NOT_FOUND) {
            return JsonRpcError.methodNotFound("Method '" + methodName + "' not found (delegate returned 404)");
        } else if (status == HttpStatus.BAD_REQUEST) {
             String message = "Invalid parameters for method '" + methodName + "' (delegate returned 400)";
             if (!content.isEmpty()) {
                 message += ": " + content;
             }
             return JsonRpcError.invalidParams(message);
        } else if (status.isClientError()) {
             String message = "Client error during delegate execution for method '" + methodName + "' (delegate returned " + status + ")";
              if (!content.isEmpty()) {
                  message += ": " + content;
              }
             return JsonRpcError.invalidRequest(message);
        } else {
             String message = "Internal server error during delegate execution for method '" + methodName + "' (delegate returned " + status + ")";
             if (!content.isEmpty()) {
                 message += ": " + content;
             }
            return JsonRpcError.internalError(message);
        }
    }

    /**
     * Custom exception to wrap parsing/validation errors and carry the pre-formatted JsonRpcResponse.
     */
    private static class JsonRpcRequestParseException extends Exception {
        private final JsonRpcResponse errorResponse;
        @Nullable private final Object requestId;

        JsonRpcRequestParseException(Throwable cause, JsonRpcResponse errorResponse, @Nullable Object requestId) {
            super(cause.getMessage(), cause);
            this.errorResponse = errorResponse;
            this.requestId = requestId;
        }

        JsonRpcResponse getErrorResponse() {
            return errorResponse;
        }

        @Nullable
        Object getRequestId() {
            return requestId;
        }
    }
} 