package com.linecorp.armeria.server.http.dynamic;

import java.util.Map;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Path variable-enabled function. When implementing, make sure to run logic inside
 * {@code ctx.blockingCodeExecutor()} if it may block.
 *
 * <p>Mapped path variables are passed via arguments parameter.
 */
@FunctionalInterface
public interface DynamicHttpFunction {

    /**
     * Serves an incoming {@link HttpRequest}, with given mapped path variables {@code args}.
     */
    Object serve(ServiceRequestContext ctx, HttpRequest req, Map<String, String> args) throws Exception;
}
