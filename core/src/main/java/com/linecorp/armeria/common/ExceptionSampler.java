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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Function;

import org.jctools.maps.NonBlockingHashMap;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Sampler;

final class ExceptionSampler implements Sampler<Class<? extends Throwable>> {

    private final Map<Class<? extends Throwable>, Sampler<Class<? extends Throwable>>> samplers =
            new NonBlockingHashMap<>();
    private final Function<? super Class<? extends Throwable>,
            ? extends Sampler<Class<? extends Throwable>>> samplerFactory;
    private final String spec;

    ExceptionSampler(String spec) {
        samplerFactory = unused -> Sampler.of(spec);
        this.spec = spec;
    }

    @Override
    public boolean isSampled(Class<? extends Throwable> exceptionType) {
        requireNonNull(exceptionType, "exceptionType");
        return samplers.computeIfAbsent(exceptionType, samplerFactory).isSampled(exceptionType);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).addValue(spec).toString();
    }
}
