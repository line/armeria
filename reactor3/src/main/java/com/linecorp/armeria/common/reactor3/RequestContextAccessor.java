package com.linecorp.armeria.common.reactor3;

import java.util.Objects;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.micrometer.context.ThreadLocalAccessor;

public class RequestContextAccessor implements ThreadLocalAccessor<RequestContext> {

    private static final String KEY = "ARMERIA_REQUEST_CONTEXT";
    private static final RequestContextAccessor instance = createInstance();

    private static RequestContextAccessor createInstance() {
        return new RequestContextAccessor();
    }

    public static RequestContextAccessor getInstance() {
        return instance;
    }

    private RequestContextAccessor() {
    }

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public RequestContext getValue() {
        return RequestContextUtil.get();
    }

    @Override
    public void setValue(RequestContext value) {
        RequestContextUtil.getAndSet(value);
    }

    @Override
    public void setValue() {
        // NO Operation.
    }

    @Override
    public void restore(RequestContext previousValue) {
        RequestContextUtil.getAndSet(previousValue);
    }

    @Override
    public void restore() {
        RequestContextUtil.pop();
    }
}
