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

package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Locale;

import com.google.common.base.Splitter;

/**
 * A utility class to provide useful {@link Sampler} implementations and functionalities to {@link Sampler}.
 */
final class Samplers {

    private static final Splitter KEY_VALUE_SPLITTER = Splitter.on('=').trimResults();
    private static final String IAE_MSG_TEMPLATE =
            "specification: %s (expected: always, never, random=<probability> or " +
            "rate-limited=<samples_per_second>";

    /**
     * A sampler that will always be sampled.
     */
    @SuppressWarnings("rawtypes")
    static final Sampler ALWAYS = new Sampler() {
        @Override
        public boolean isSampled(Object ignored) {
            return true;
        }

        @Override
        public String toString() {
            return "always";
        }
    };

    /**
     * A sampler that will never be sampled.
     */
    @SuppressWarnings("rawtypes")
    static final Sampler NEVER = new Sampler() {
        @Override
        public boolean isSampled(Object ignored) {
            return false;
        }

        @Override
        public String toString() {
            return "never";
        }
    };

    static <T> Sampler<T> of(String specification) {
        requireNonNull(specification, "specification");
        switch (specification.trim()) {
            case "always":
            case "true":
                return Sampler.always();
            case "never":
            case "false":
                return Sampler.never();
        }

        final List<String> components = KEY_VALUE_SPLITTER.splitToList(specification);
        checkArgument(components.size() == 2, IAE_MSG_TEMPLATE, specification);

        final String key = components.get(0);
        final String value = components.get(1);
        try {
            switch (key) {
                case "random":
                    return Sampler.random(Float.parseFloat(value));
                case "rate-limit":
                case "rate-limiting":
                case "rate-limited":
                    return Sampler.rateLimiting(Integer.parseInt(value));
                default:
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, IAE_MSG_TEMPLATE, specification), e);
        }

        throw new IllegalArgumentException(
                String.format(Locale.ROOT, IAE_MSG_TEMPLATE, specification));
    }

    private Samplers() {}
}
