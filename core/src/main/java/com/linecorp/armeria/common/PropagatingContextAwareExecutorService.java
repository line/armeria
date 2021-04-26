package com.linecorp.armeria.common;

import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

public class PropagatingContextAwareExecutorService extends AbstractContextAwareExecutorService {
    PropagatingContextAwareExecutorService(ExecutorService executor) {
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
