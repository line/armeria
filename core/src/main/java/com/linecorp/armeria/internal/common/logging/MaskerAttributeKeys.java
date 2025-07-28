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

package com.linecorp.armeria.internal.common.logging;

import com.linecorp.armeria.common.RequestContext;

import io.netty.util.AttributeKey;

public final class MaskerAttributeKeys {

    public static final AttributeKey<RequestContext> REQUEST_CONTEXT_KEY =
            AttributeKey.valueOf(MaskerAttributeKeys.class, "REQUEST_CONTEXT_KEY");

    private MaskerAttributeKeys() {}
}
