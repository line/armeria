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

package com.linecorp.armeria.common.logging;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;

final class PreviewSpec {

    private final BiPredicate<? super RequestContext, ? super HttpHeaders> predicate;
    private final PreviewMode mode;
    @Nullable
    private final BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer;

    PreviewSpec(BiPredicate<? super RequestContext, ? super HttpHeaders> predicate,
                PreviewMode mode, @Nullable BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer) {
        this.predicate = predicate;
        this.mode = mode;
        this.producer = producer;
    }

    BiPredicate<? super RequestContext, ? super HttpHeaders> predicate() {
        return predicate;
    }

    PreviewMode mode() {
        return mode;
    }

    @Nullable
    BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer() {
        return producer;
    }

    enum PreviewMode {
        TEXT,
        BINARY,
        DISABLED
    }
}
