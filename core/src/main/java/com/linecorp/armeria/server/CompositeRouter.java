/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A {@link Router} implementation that enables composing multiple {@link Router}s into one.
 */
final class CompositeRouter<I, O> implements Router<O> {

    private final List<Router<I>> delegates;
    private final Function<Routed<I>, Routed<O>> resultMapper;

    CompositeRouter(List<Router<I>> delegates, Function<Routed<I>, Routed<O>> resultMapper) {
        this.delegates = requireNonNull(delegates, "delegates");
        this.resultMapper = requireNonNull(resultMapper, "resultMapper");
    }

    @Override
    public Routed<O> find(RoutingContext routingCtx) {
        for (Router<I> delegate : delegates) {
            final Routed<I> result = delegate.find(routingCtx);
            if (result.isPresent()) {
                return resultMapper.apply(result);
            }
        }

        return Routed.empty();
    }

    @Override
    public List<Routed<O>> findAll(RoutingContext routingContext) {
        // TODO(trustin): Optimize for the case where `delegates.size() == 0 or 1`
        //                by using a different implementation instead of a dynamic switch.
        final int numDelegates = delegates.size();
        switch (numDelegates) {
            case 0:
                return ImmutableList.of();
            case 1:
                return delegates.get(0).findAll(routingContext).stream()
                                .map(resultMapper)
                                .collect(toImmutableList());
            default:
                return delegates.stream()
                                .flatMap(delegate -> delegate.findAll(routingContext).stream())
                                .map(resultMapper)
                                .collect(toImmutableList());
        }
    }

    @Override
    public boolean registerMetrics(MeterRegistry registry, MeterIdPrefix idPrefix) {
        final int numDelegates = delegates.size();
        switch (numDelegates) {
            case 0:
                return false;
            case 1:
                return delegates.get(0).registerMetrics(registry, idPrefix);
            default:
                boolean registered = false;
                for (int i = 0; i < numDelegates; i++) {
                    final MeterIdPrefix delegateIdPrefix = idPrefix.withTags("index", String.valueOf(i));
                    if (delegates.get(i).registerMetrics(registry, delegateIdPrefix)) {
                        registered = true;
                    }
                }
                return registered;
        }
    }

    @Override
    public void dump(OutputStream output) {
        delegates.forEach(delegate -> delegate.dump(output));
    }
}
