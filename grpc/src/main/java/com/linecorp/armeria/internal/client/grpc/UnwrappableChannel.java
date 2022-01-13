/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.client.grpc;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Unwrappable;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.MethodDescriptor;

final class UnwrappableChannel extends Channel implements Unwrappable {

    private final Channel delegate;
    private final ArmeriaChannel underlying;

    UnwrappableChannel(Channel delegate, ArmeriaChannel underlying) {
        this.delegate = delegate;
        this.underlying = underlying;
    }

    @Override
    public <I, O> ClientCall<I, O> newCall(
            MethodDescriptor<I, O> methodDescriptor, CallOptions callOptions) {
        return delegate.newCall(methodDescriptor, callOptions);
    }

    @Override
    public String authority() {
        return delegate.authority();
    }

    @Nullable
    @Override
    public <T> T as(Class<T> type) {
        return underlying.as(type);
    }
}
