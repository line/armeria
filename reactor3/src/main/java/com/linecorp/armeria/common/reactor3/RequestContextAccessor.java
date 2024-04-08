/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.reactor3;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * TBD.
 */
public final class RequestContextAccessor implements ThreadLocalAccessor<RequestContext> {

    private static final String KEY = "ARMERIA_REQUEST_CONTEXT";
    private static final RequestContextAccessor instance = createInstance();

    private static RequestContextAccessor createInstance() {
        return new RequestContextAccessor();
    }

    /**
     * TBD.
     */
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
        // This method is called when DefaultScope is closed and no previous value existed in
        // ThreadLocal State.
    }

    @Override
    public void restore(RequestContext previousValue) {
        RequestContextUtil.getAndSet(previousValue);
    }

    /*
    @Override
    public void restore() {
        // Use super.restore() instead of implementing on child class.
        // This method is called when DefaultContextSnapshot.DefaultContextScope
        // is closed and no previous value existed in ThreadLocal State.
    }
     */
}
