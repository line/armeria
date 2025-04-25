package com.linecorp.armeria.server.jsonrpc;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class JsonRpcServiceBuilder {

    private final Map<String, HttpService> cachedRoutes = new LinkedHashMap<>();
    private final Map<String, Object> annotatedServices = new LinkedHashMap<>();
    private final ServerBuilder serverBuilder;

    public JsonRpcServiceBuilder(ServerBuilder sb) {
        serverBuilder = sb;
    }

    public JsonRpcServiceBuilder addService(String prefix, HttpService service) {
        cachedRoutes.put(prefix, service);
        return this;
    }

    public JsonRpcServiceBuilder addAnnotatedService(String prefix, Object service) {
        annotatedServices.put(prefix, service);
        return this;
    }

    public HttpService build() {
        for (Map.Entry<String, Object> entry : annotatedServices.entrySet()) {
            serverBuilder.annotatedService(entry.getKey(), entry.getValue());
        }


        return (ctx, req) -> HttpResponse.of(req.aggregate().thenCompose(aggregatedRequest -> {
            final String prefix = ctx.mappedPath();
            final String newPath = ctx.mappedPath() + "/" + aggregatedRequest.contentUtf8();

            HttpService target = cachedRoutes.get(prefix);
            if (target == null) {
                HttpService found = ctx.config().server().serviceConfigs().stream()
                        .filter(s -> s.route().patternString().equals(newPath))
                        .map(ServiceConfig::service)
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    target = found;
                    cachedRoutes.put(prefix, target);
                } else {
                    return CompletableFuture.completedFuture(HttpResponse.of(HttpStatus.NOT_FOUND));
                }
            }

            ctx.mappedPath();
            final RequestHeaders headers = RequestHeaders.builder()
                    .method(req.method())
                    .path(newPath)
                    .build();

            final HttpRequest request = aggregatedRequest.toHttpRequest(headers);
            try {
                return target.serve(ServiceRequestContext.of(request), request)
                        .aggregate()
                        .thenApply(aggregatedResponse -> HttpResponse.of(aggregatedResponse.headers(), aggregatedResponse.content()));
            } catch (Exception ignored) {
                return CompletableFuture.completedFuture(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
            }
        }));
    }
}
