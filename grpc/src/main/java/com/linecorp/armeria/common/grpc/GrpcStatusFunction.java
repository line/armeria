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

package com.linecorp.armeria.common.grpc;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Status;

/**
 * A mapping function that converts a {@link Throwable} into a gRPC {@link Status}.
 */
@UnstableApi
@FunctionalInterface
public interface GrpcStatusFunction extends Function<Throwable, Status> {

    /**
     * Maps the specified {@link Throwable} to a gRPC {@link Status}.
     * If {@code null} is returned, the built-in mapping rule is used by default.
     */
    @Nullable
    @Override
    Status apply(Throwable throwable);
}
