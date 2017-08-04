/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link Router} implementation that enables composing multiple {@link Router}s into one.
 */
class CompositeRouter<I, O> implements Router<O> {

    private final List<Router<I>> delegates;
    private final Function<PathMapped<I>, PathMapped<O>> resultMapper;

    CompositeRouter(Router<I> delegate, Function<PathMapped<I>, PathMapped<O>> resultMapper) {
        this(ImmutableList.of(requireNonNull(delegate, "delegate")), resultMapper);
    }

    CompositeRouter(List<Router<I>> delegates, Function<PathMapped<I>, PathMapped<O>> resultMapper) {
        this.delegates = requireNonNull(delegates, "delegates");
        this.resultMapper = requireNonNull(resultMapper, "resultMapper");
    }

    @Override
    public PathMapped<O> find(PathMappingContext mappingCtx) {
        for (Router<I> delegate : delegates()) {
            final PathMapped<I> result = delegate.find(mappingCtx);
            if (result.isPresent()) {
                return resultMapper.apply(result);
            }
        }
        mappingCtx.delayedThrowable().ifPresent(Exceptions::throwUnsafely);
        return PathMapped.empty();
    }

    @Override
    public void dump(OutputStream output) {
        delegates().forEach(delegate -> delegate.dump(output));
    }

    protected final List<Router<I>> delegates() {
        return delegates;
    }
}
