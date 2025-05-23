/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.hedging;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;


@FunctionalInterface
public interface HedgingConfigMapping<T extends Response> {
    static <T extends Response> HedgingConfigMapping<T> of(
            BiFunction<? super ClientRequestContext, Request, String> keyFactory,
            BiFunction<? super ClientRequestContext, Request, HedgingConfig<T>> hedgingConfigFactory) {
        return new KeyedHedgingConfigMapping<>(keyFactory, hedgingConfigFactory);
    }

    HedgingConfig<T> get(ClientRequestContext ctx, Request req);
}
