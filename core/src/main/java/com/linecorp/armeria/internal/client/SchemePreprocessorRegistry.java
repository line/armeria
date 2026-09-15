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

package com.linecorp.armeria.internal.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.SchemePreprocessorProvider;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;

public final class SchemePreprocessorRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SchemePreprocessorRegistry.class);

    private static final Map<String, Match> MATCHES;

    static {
        final ImmutableList<SchemePreprocessorProvider> providers = ImmutableList.copyOf(
                ServiceLoader.load(SchemePreprocessorProvider.class,
                                   SchemePreprocessorProvider.class.getClassLoader()));
        if (!providers.isEmpty()) {
            logger.debug("Available {}s: {}",
                         SchemePreprocessorProvider.class.getSimpleName(), providers);
        }

        final ImmutableMap.Builder<String, Match> map = ImmutableMap.builder();
        for (SchemePreprocessorProvider provider : providers) {
            final String scheme = provider.scheme();
            checkArgument(scheme.equals(Ascii.toLowerCase(scheme)),
                          "SchemePreprocessorProvider.scheme() must be lowercase: %s", scheme);
            map.put(scheme, new Match(provider, SerializationFormat.NONE));
            for (SerializationFormat sf : SerializationFormat.values()) {
                map.put(sf.uriText() + '+' + scheme, new Match(provider, sf));
            }
        }
        MATCHES = map.buildOrThrow();
    }

    @Nullable
    public static Match find(String uriScheme) {
        requireNonNull(uriScheme, "uriScheme");
        return MATCHES.get(Ascii.toLowerCase(uriScheme));
    }

    public static final class Match {
        private final SchemePreprocessorProvider provider;
        private final SerializationFormat serializationFormat;

        Match(SchemePreprocessorProvider provider, SerializationFormat serializationFormat) {
            this.provider = provider;
            this.serializationFormat = serializationFormat;
        }

        public SchemePreprocessorProvider provider() {
            return provider;
        }

        public SerializationFormat serializationFormat() {
            return serializationFormat;
        }
    }

    private SchemePreprocessorRegistry() {}
}
