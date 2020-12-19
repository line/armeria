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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * An {@link HttpService} that handles transient requests, for example, health check requests.
 */
@FunctionalInterface
public interface TransientHttpService extends TransientService<HttpRequest, HttpResponse>, HttpService {

    /**
     * Returns a new {@link HttpService} decorator which makes the specified {@link HttpService} as
     * {@link TransientService}.
     */
    static Function<? super HttpService, SimpleDecoratingHttpService> newDecorator(
            TransientServiceOption... transientServiceOptions) {
        requireNonNull(transientServiceOptions, "transientServiceOptions");
        return newDecorator(ImmutableSet.copyOf(transientServiceOptions));
    }

    /**
     * Returns a new {@link HttpService} decorator which makes the specified {@link HttpService} as
     * {@link TransientService}.
     */
    static Function<? super HttpService, SimpleDecoratingHttpService> newDecorator(
            Iterable<TransientServiceOption> transientServiceOptions) {
        requireNonNull(transientServiceOptions, "transientServiceOptions");
        return delegate -> new WrappingTransientHttpService(delegate,
                                                            Sets.immutableEnumSet(transientServiceOptions));
    }
}
