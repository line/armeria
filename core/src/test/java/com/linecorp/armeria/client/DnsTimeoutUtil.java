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
 * under the License
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnknownHostException;

import com.google.common.base.Throwables;

import io.netty.resolver.dns.DnsNameResolverTimeoutException;

final class DnsTimeoutUtil {

    static void assertDnsTimeoutException(Throwable cause) {
        final Throwable rootCause = Throwables.getRootCause(cause);
        if (rootCause instanceof UnknownHostException) {
            final Throwable suppressed = rootCause.getSuppressed()[0];
            assertThat(suppressed.getClass().getSimpleName())
                    .isEqualTo("SearchDomainUnknownHostException");
            assertThat(suppressed.getCause()).isInstanceOf(DnsNameResolverTimeoutException.class);
        } else {
            assertThat(rootCause).isInstanceOfAny(DnsTimeoutException.class,
                                                  DnsNameResolverTimeoutException.class);
        }
    }

    private DnsTimeoutUtil() {}
}
