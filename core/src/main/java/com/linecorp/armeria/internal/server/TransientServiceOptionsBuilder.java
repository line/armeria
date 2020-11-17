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
package com.linecorp.armeria.internal.server;

import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.server.TransientServiceBuilder;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * A Builder for {@link TransientServiceOption}.
 */
public final class TransientServiceOptionsBuilder implements TransientServiceBuilder {

    @Nullable
    private Set<TransientServiceOption> transientServiceOptions;

    @Override
    public TransientServiceOptionsBuilder transientServiceOptions(
            TransientServiceOption... transientServiceOptions) {
        return transientServiceOptions(
                ImmutableSet.copyOf(requireNonNull(transientServiceOptions, "transientServiceOptions")));
    }

    @Override
    public TransientServiceOptionsBuilder transientServiceOptions(
            Iterable<TransientServiceOption> transientServiceOptions) {
        if (this.transientServiceOptions == null) {
            this.transientServiceOptions = EnumSet.noneOf(TransientServiceOption.class);
        }
        this.transientServiceOptions.addAll(
                ImmutableSet.copyOf(requireNonNull(transientServiceOptions, "transientServiceOptions")));
        return this;
    }

    /**
     * Returns the {@link Set} of {@link TransientServiceOption}s set so far.
     * {@link Flags#transientServiceOptions()} if not set.
     */
    public Set<TransientServiceOption> build() {
        if (transientServiceOptions == null) {
            return Flags.transientServiceOptions();
        }
        return Sets.immutableEnumSet(transientServiceOptions);
    }
}
