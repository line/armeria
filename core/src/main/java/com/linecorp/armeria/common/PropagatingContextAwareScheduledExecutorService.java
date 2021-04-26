package com.linecorp.armeria.common;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

final class PropagatingContextAwareScheduledExecutorService
        extends AbstractContextAwareScheduledExecutorService {
    PropagatingContextAwareScheduledExecutorService(ScheduledExecutorService executor) {
        super(executor);
    }

    @Override
    @Nullable
    public RequestContext context() {
        return RequestContext.currentOrNull();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("executor", executor)
                          .toString();
    }
}
