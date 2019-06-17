/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import java.util.Optional;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.retrofit2.metric.RetrofitMeterIdPrefixFunction;
import com.linecorp.armeria.common.logging.RequestLog;

import io.netty.util.AttributeKey;
import retrofit2.Invocation;

/**
 * Armeria Retrofit automatically adds an invocation to {@link RequestLog}. You can retrieve
 * the invocation by {@link InvocationAttribute#getInvocation(RequestLog)} for metrics and monitoring.
 *
 * @see Invocation
 */
public final class InvocationAttribute {

    private static final AttributeKey<Invocation> RETROFIT_INVOCATION =
            AttributeKey.valueOf(RetrofitMeterIdPrefixFunction.class, "RETROFIT_INVOCATION");

    /**
     * Put {@code Invocation} to {@code RequestLog} if not null.
     */
    static void setInvocation(RequestLog log, @Nullable Invocation invocation) {
        if (invocation == null) {
            return;
        }
        log.attr(RETROFIT_INVOCATION).set(invocation);
    }

    /**
     * Get {@code Invocation} from {@code RequestLog}.
     */
    public static Optional<Invocation> getInvocation(RequestLog log) {
        return Optional.ofNullable(log.attr(RETROFIT_INVOCATION).get());
    }

    private InvocationAttribute() {}
}
