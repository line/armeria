/*
 * Copyright 2022 LINE Corporation
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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Sampler;

/**
 * The configuration that determines whether to trace request context leaks and how frequently to
 * keeps stack trace.
 */
public final class LeakDetectionConfiguration {

    private static final LeakDetectionConfiguration DISABLE_INSTANCE =
            new LeakDetectionConfiguration(false, null);

    /**
     * Return the instance of disabled {@link LeakDetectionConfiguration}.
     */
    public static LeakDetectionConfiguration disable() {
        return DISABLE_INSTANCE;
    }

    /**
     * Return the instance of enabled {@link LeakDetectionConfiguration} with specified {@link Sampler}.
     */
    public static LeakDetectionConfiguration enable(Sampler<?> sampler) {
        return new LeakDetectionConfiguration(true, requireNonNull(sampler, "sampler"));
    }

    private final Boolean isEnable;
    private final Sampler<?> sampler;

    LeakDetectionConfiguration(boolean isEnable, Sampler<?> sampler) {
        this.isEnable = isEnable;
        this.sampler = sampler;
    }

    /**
     * Return the whether {@link LeakDetectionConfiguration} is enabled.
     */
    public boolean isEnable() {
        return isEnable;
    }

    /**
     * Return the {@link Sampler}. If this {@link LeakDetectionConfiguration} is disabled then return null.
     */
    public Sampler<?> sampler() {
        return sampler;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("isEnable", isEnable)
                          .add("sampler", sampler)
                          .toString();
    }
}
