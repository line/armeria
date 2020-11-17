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
import com.linecorp.armeria.server.OptOutFeature;
import com.linecorp.armeria.server.TransientServiceBuilder;

/**
 * A Builder for {@link OptOutFeature}.
 */
public final class OptOutFeaturesBuilder implements TransientServiceBuilder {

    @Nullable
    private Set<OptOutFeature> optOutFeatures;

    @Override
    public OptOutFeaturesBuilder optOutFeatures(OptOutFeature... optOutFeatures) {
        return optOutFeatures(ImmutableSet.copyOf(requireNonNull(optOutFeatures, "optOutFeatures")));
    }

    @Override
    public OptOutFeaturesBuilder optOutFeatures(Iterable<OptOutFeature> optOutFeatures) {
        if (this.optOutFeatures == null) {
            this.optOutFeatures = EnumSet.noneOf(OptOutFeature.class);
        }
        this.optOutFeatures.addAll(ImmutableSet.copyOf(requireNonNull(optOutFeatures, "optOutFeatures")));
        return this;
    }

    /**
     * Returns the {@link Set} of {@link OptOutFeature}s set so far. {@link Flags#optOutFeatures()} if not set.
     */
    public Set<OptOutFeature> optOutFeatures() {
        if (optOutFeatures == null) {
            return Flags.optOutFeatures();
        }
        return Sets.immutableEnumSet(optOutFeatures);
    }
}
