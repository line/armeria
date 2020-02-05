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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.RequestContext;

/**
 * Implementation of {@link JavaVersionSpecific} using Java 9 APIs.
 */
class Java9VersionSpecific extends JavaVersionSpecific {

    @Override
    String name() {
        return "Java 9+";
    }

    @Override
    public final long currentTimeMicros() {
        final Instant now = Clock.systemUTC().instant();
        return TimeUnit.SECONDS.toMicros(now.getEpochSecond()) + TimeUnit.NANOSECONDS.toMicros(
                now.getNano());
    }

    @Override
    public final <T> CompletableFuture<T> newRequestContextAwareFuture(RequestContext ctx) {
        return new Java9RequestContextAwareFuture<>(requireNonNull(ctx, "ctx"));
    }
}
