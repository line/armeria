package com.linecorp.armeria.server;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

final class ExceptionStats {
    private Map<String, ExceptionContext> exceptions;
    private final ReentrantShortLock lock;

    public ExceptionStats(Map<String, ExceptionContext> exceptions, ReentrantShortLock lock) {
        this.exceptions = exceptions;
        this.lock = lock;
    }

    void record(ServiceRequestContext ctx, Throwable cause) {
        lock.lock();
        try {
            exceptions.compute(Exceptions.traceText(cause), (key, value) -> {
                if (value != null) {
                    value.incrementCounter();
                    return value;
                } else {
                    ExceptionContext newValue = new ExceptionContext(cause, ctx);
                    return newValue;
                }
            });
        } finally {
            lock.unlock();
        }
    }

    Collection<ExceptionContext> dump() {
        lock.lock();
        try {
            final Map<String, ExceptionContext> oldExceptions = exceptions;
            exceptions = new HashMap<>();
            return oldExceptions.values();
        } finally {
            lock.unlock();
        }
    }

    static class ExceptionContext {
        private final Throwable exception;
        private final ServiceRequestContext ctx;
        private long counter = 1;

        public ExceptionContext(Throwable exception, ServiceRequestContext ctx) {
            this.exception = exception;
            this.ctx = ctx;
        }

        public void incrementCounter() {
            counter++;
        }

        public Throwable getException() {
            return exception;
        }

        public long getCounter() {
            return counter;
        }
    }
}
