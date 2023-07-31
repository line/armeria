/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.server.thrift.THttpService;

import testing.spring.thrift.TestService.AsyncIface;
import testing.spring.thrift.TestService.Iface;

class ThriftServiceUtilsTest {
    private static final String SERVICE_NAME = "testing.spring.thrift.TestService";

    private final AsyncIface asyncService = (name, cb) -> cb.onComplete("hello");
    private final Iface syncService = name -> "hello";

    @Test
    void serviceNames() {
        assertThat(ThriftServiceUtils.serviceNames(THttpService.of(asyncService)))
                .isEqualTo(ImmutableSet.of(SERVICE_NAME));
        assertThat(ThriftServiceUtils.serviceNames(THttpService.builder()
                                                               .addService("async", asyncService)
                                                               .addService("sync", syncService).build()))
                .isEqualTo(ImmutableSet.of(SERVICE_NAME));
    }
}
