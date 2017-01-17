package com.linecorp.armeria.server.http.dynamic;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpStatus;
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
        final DefaultHttpResponse res = new DefaultHttpResponse();
        ctx.blockingTaskExecutor().execute(() -> {
            try {
                Object ret = function.serve(ctx, req, args);
                if (!(ret instanceof HttpResponse)) {
                    throw new IllegalStateException("Illegal return type: " + ret.getClass().getSimpleName());
                }
                AggregatedHttpMessage msg = ((HttpResponse) ret).aggregate().get();
                res.write(msg.headers());
                res.write(msg.content());
            } catch (Throwable e) {
                final long current = System.currentTimeMillis();
                HttpHeaders headers = HttpHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR)
                                                 .setTimeMillis(HttpHeaderNames.DATE, current);
                res.write(headers);
            } finally {
                res.close();
            }
        });
        return res;
    }
}
