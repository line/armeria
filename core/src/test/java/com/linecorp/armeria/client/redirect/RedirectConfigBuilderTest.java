/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client.redirect;

import static com.linecorp.armeria.internal.client.RedirectingClientUtil.allowAllDomains;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class RedirectConfigBuilderTest {

    @Test
    void redirectConfigBuilder() {
        final ClientRequestContext clientCtx =
                ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        RedirectConfig config = RedirectConfig.builder().build();
        assertThat(config.domainFilter()).isNull();

        config = RedirectConfig.builder().allowAllDomains().build();
        assertThat(config.domainFilter()).isSameAs(allowAllDomains);

        config = RedirectConfig.builder()
                               .allowAllDomains()
                               .allowDomains("foo.com")
                               .allowDomains((ctx, domain) -> false)
                               .build();
        assertThat(config.domainFilter()).isSameAs(allowAllDomains);

        config = RedirectConfig.builder()
                               .allowDomains("foo.com")
                               .allowDomains(ImmutableSet.of("bar.com"))
                               .build();
        BiPredicate<ClientRequestContext, String> filter = config.domainFilter();
        assertThat(filter).isNotSameAs(allowAllDomains);
        assertThat(filter.test(clientCtx, "http://foo.com")).isTrue();
        assertThat(filter.test(clientCtx, "http://bar.com")).isTrue();
        assertThat(filter.test(clientCtx, "http://qux.com")).isFalse();

        config = RedirectConfig.builder()
                               .allowDomains((ctx, domain) -> domain.contains("qux.com"))
                               .build();
        filter = config.domainFilter();
        assertThat(filter.test(clientCtx, "http://foo.com")).isFalse();
        assertThat(filter.test(clientCtx, "http://bar.com")).isFalse();
        assertThat(filter.test(clientCtx, "http://qux.com")).isTrue();

        config = RedirectConfig.builder()
                               .allowDomains("foo.com")
                               .allowDomains(ImmutableSet.of("bar.com"))
                               .allowDomains((ctx, domain) -> domain.contains("qux.com"))
                               .build();
        filter = config.domainFilter();
        assertThat(filter).isNotSameAs(allowAllDomains);
        assertThat(filter.test(clientCtx, "http://foo.com")).isTrue();
        assertThat(filter.test(clientCtx, "http://bar.com")).isTrue();
        assertThat(filter.test(clientCtx, "http://qux.com")).isTrue();
    }
}
