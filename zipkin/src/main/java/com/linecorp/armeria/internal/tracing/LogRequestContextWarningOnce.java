package com.linecorp.armeria.internal.tracing;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext;

public enum LogRequestContextWarningOnce implements Supplier<RequestContext> {

    INSTANCE;

    static final Logger logger = LoggerFactory.getLogger(RequestContextCurrentTraceContext.class);

    @Override
    public RequestContext get() {
        ClassLoaderHack.loadMe();
        return null;
    }

    /**
     * This won't be referenced until {@link #get()} is called. If there's only one classloader, the initializer
     * will only be called once.
     */
    static class ClassLoaderHack {
        static void loadMe() {
        }

        static {
            logger.warn("Attempted to propagate trace context, but no request context available. " +
                        "Did you remember to use RequestContext.contextAwareExecutor() or RequestContext.makeContextAware()");
        }
    }
}