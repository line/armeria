/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.client;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;

final class ExecutionProtocolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionProtocolRegistry.class);

    static final Map<String, ExecutionProtocol> URI_TEXT_TO_PROTOCOLS;
    static final Set<ExecutionProtocol> VALUES;

    static {
        final ImmutableMap.Builder<String, ExecutionProtocol> map = ImmutableMap.builder();

        // Register all SessionProtocol values first.
        for (SessionProtocol sp : SessionProtocol.values()) {
            map.put(sp.uriText(), sp);
        }

        // Load additional execution protocols from SPI providers.
        final ImmutableList<ExecutionProtocolProvider> providers = ImmutableList.copyOf(
                ServiceLoader.load(ExecutionProtocolProvider.class,
                                   ExecutionProtocolProvider.class.getClassLoader()));
        if (!providers.isEmpty()) {
            logger.debug("Available {}s: {}",
                         ExecutionProtocolProvider.class.getSimpleName(), providers);

            for (ExecutionProtocolProvider provider : providers) {
                final ExecutionProtocol protocol = provider.protocol();
                final String uriText = Ascii.toLowerCase(protocol.uriText());
                map.put(uriText, protocol);
            }
        }

        URI_TEXT_TO_PROTOCOLS = map.buildOrThrow();
        VALUES = ImmutableSet.copyOf(URI_TEXT_TO_PROTOCOLS.values());
    }

    private ExecutionProtocolRegistry() {}
}
