package com.linecorp.armeria.common;

import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;

public class PropagatingContextAwareExecutorService extends AbstractContextAwareExecutorService {
    PropagatingContextAwareExecutorService(ExecutorService executor) {
        super(executor);
    }

    @Override
    @Nullable
    public RequestContext context() {
        return RequestContext.currentOrNull();
    }
}
