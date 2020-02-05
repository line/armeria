/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.common.brave;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

import brave.propagation.Propagation;
import io.netty.util.AsciiString;

/**
 * Converter from {@link String} to {@link AsciiString} which is used by Brave to marshall trace information
 * into Armeria's {@link HttpHeaders}.
 */
public enum AsciiStringKeyFactory implements Propagation.KeyFactory<AsciiString> {
    INSTANCE;

    @Override
    public AsciiString create(String name) {
        return HttpHeaderNames.of(name);
    }
}
