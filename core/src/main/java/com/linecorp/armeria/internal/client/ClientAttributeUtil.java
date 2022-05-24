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

package com.linecorp.armeria.internal.client;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.AttributeKey;

public final class ClientAttributeUtil {

    public static final AttributeKey<Throwable> UNPROCESSED_THROWABLE_KEY =
            AttributeKey.valueOf(ClientAttributeUtil.class, "UNPROCESSED_THROWABLE");

    public static void set(ClientRequestContext ctx, Throwable cause) {
        ctx.setAttr(UNPROCESSED_THROWABLE_KEY, Exceptions.peel(cause));
    }

    @Nullable
    public static Throwable throwable(RequestContext ctx) {
        return ctx.attr(UNPROCESSED_THROWABLE_KEY);
    }

    private ClientAttributeUtil() {}
}
