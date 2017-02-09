package com.linecorp.armeria.server.http.dynamic;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.http.DeferredHttpResponse;
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
        final DeferredHttpResponse res = new DeferredHttpResponse();
        try {
            Object ret = function.serve(ctx, req, args);
            if (!(ret instanceof CompletionStage)) {
                throw new IllegalStateException("Illegal return type: " + ret.getClass().getSimpleName());
            }
            ((CompletionStage<HttpResponse>) ret)
                    .whenComplete((httpResponse, t) -> {
                        if (t != null) {
                            res.close(t);
                        } else {
                            res.delegate(httpResponse);
                        }
                    });
        } catch (Throwable e) {
            res.close(e);
        }
        return res;
    }
}
