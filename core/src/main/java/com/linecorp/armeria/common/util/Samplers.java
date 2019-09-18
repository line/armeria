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

/**
 * A utility class to provide default {@link Sampler}.
 */
final class Samplers {

    private Samplers() {}

    /**
     * A sampler that will always be sampled.
     */
    static final Sampler ALWAYS = new Sampler() {
        @Override
        public boolean isSampled(Object ignored) {
            return true;
        }

        @Override
        public String toString() {
            return "AlwaysSample";
        }
    };

    /**
     * A sampler that will never be sampled.
     */
    static final Sampler NEVER = new Sampler() {
        @Override
        public boolean isSampled(Object ignored) {
            return false;
        }

        @Override
        public String toString() {
            return "NeverSample";
        }
    };
}
