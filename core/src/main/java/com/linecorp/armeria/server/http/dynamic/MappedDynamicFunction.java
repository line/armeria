package com.linecorp.armeria.server.http.dynamic;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

/**
 * {@link DynamicHttpFunction} with args.
 */
final class MappedDynamicFunction extends AbstractHttpService {

    /**
     * {@link DynamicHttpFunction} instance that will be invoked with given args.
     */
    private final DynamicHttpFunction function;

    /**
     * Arguments, represented in Map of variable name to its value.
     */
    private final Map<String, String> args;

    MappedDynamicFunction(DynamicHttpFunction function, Map<String, String> args) {
        this.function = requireNonNull(function, "function");
        this.args = requireNonNull(args, "args");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Object ret = function.serve(ctx, req, args);
        if (!(ret instanceof CompletionStage)) {
            return HttpResponse.ofFailure(new IllegalStateException(
                    "illegal return type: " + ret.getClass().getSimpleName()));
        }

        @SuppressWarnings("unchecked")
        CompletionStage<HttpResponse> castStage = (CompletionStage<HttpResponse>) ret;
        return HttpResponse.from(castStage);
    }
}
