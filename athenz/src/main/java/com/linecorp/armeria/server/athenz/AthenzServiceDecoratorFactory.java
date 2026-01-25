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

package com.linecorp.armeria.server.athenz;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;

import io.netty.util.AsciiString;

/**
 * A factory for creating a decorator that checks access permissions using Athenz policies.
 * This factory is used in conjunction with the {@link RequiresAthenzRole} annotation.
 *
 * @see RequiresAthenzRole
 */
@UnstableApi
public final class AthenzServiceDecoratorFactory implements DecoratorFactoryFunction<RequiresAthenzRole> {

    /**
     * Returns a new {@link AthenzServiceDecoratorFactoryBuilder} with the specified {@link ZtsBaseClient}.
     */
    public static AthenzServiceDecoratorFactoryBuilder builder(ZtsBaseClient ztsBaseClient) {
        requireNonNull(ztsBaseClient, "ztsBaseClient");
        return new AthenzServiceDecoratorFactoryBuilder(ztsBaseClient);
    }

    private final AthenzAuthorizer authorizer;
    private final MeterIdPrefix meterIdPrefix;

    AthenzServiceDecoratorFactory(AthenzAuthorizer authorizer, MeterIdPrefix meterIdPrefix) {
        this.authorizer = authorizer;
        this.meterIdPrefix = meterIdPrefix;
    }

    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(RequiresAthenzRole parameter) {
        final String resource = parameter.resource();
        final String action = parameter.action();
        final TokenType[] tokenTypes = parameter.tokenType();
        final String[] customHeaders = parameter.customHeaders();

        requireNonNull(resource, "resource");
        requireNonNull(action, "action");
        checkArgument(!resource.isEmpty(), "resource must not be empty");
        checkArgument(!action.isEmpty(), "action must not be empty");

        final ImmutableList.Builder<AthenzTokenHeader> headersBuilder = ImmutableList.builder();
        if (tokenTypes.length > 0) {
            headersBuilder.add(tokenTypes);
        }
        for (String customHeader : customHeaders) {
            headersBuilder.add(new CustomAthenzTokenHeader(customHeader));
        }
        final List<AthenzTokenHeader> tokenHeaders = headersBuilder.build();
        checkArgument(!tokenHeaders.isEmpty(), "tokenType and customHeaders must not both be empty");

        return delegate -> new AthenzService(delegate, authorizer, resource, action, tokenHeaders,
                                             meterIdPrefix);
    }

    /**
     * A custom {@link AthenzTokenHeader} implementation for user-defined header names.
     */
    private static final class CustomAthenzTokenHeader implements AthenzTokenHeader {
        
        private final String headerName;
        private final AsciiString asciiHeaderName;

        CustomAthenzTokenHeader(String headerName) {
            requireNonNull(headerName, "headerName");
            checkArgument(!headerName.isEmpty(), "headerName must not be empty");
            this.headerName = headerName;
            asciiHeaderName = AsciiString.of(headerName);
        }

        @Override
        public String name() {
            return "CUSTOM_" + headerName;
        }

        @Override
        public AsciiString headerName() {
            return asciiHeaderName;
        }

        @Nullable
        @Override
        public String authScheme() {
            // Custom headers typically don't use an auth scheme
            return null;
        }

        @Override
        public boolean isRoleToken() {
            // Treat custom headers as non-role tokens by default
            return false;
        }
    }
}
