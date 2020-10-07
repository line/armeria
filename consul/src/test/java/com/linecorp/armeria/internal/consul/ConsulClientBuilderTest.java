/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.consul;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

class ConsulClientBuilderTest {

    @Test
    void consulUriShouldContainsVersionPath() throws URISyntaxException {
        assertThrows(IllegalArgumentException.class,
                     () -> ConsulClient.builder().consulUri("http://localhost:8500"));
        assertThrows(IllegalArgumentException.class,
                     () -> ConsulClient.builder().consulUri("http://localhost:8500/"));
        ConsulClient.builder().consulUri("http://localhost:8500/v1");
    }

    @Test
    void consulUriShouldSetByJustOneWay() throws URISyntaxException {
        final ConsulClientBuilder builder = ConsulClient.builder();
        builder.consulPort(8585);
        assertThrows(IllegalStateException.class, () -> builder.consulUri("http://localhost:8500/v1"));

        final ConsulClientBuilder builder2 = ConsulClient.builder();
        builder2.consulUri("http://localhost:8500/v1");
        assertThrows(IllegalStateException.class, () -> builder2.consulPort(8585));
    }

    @Test
    void consulApiVersionCanNotStartsWithSlash() {
        assertThrows(IllegalArgumentException.class, () -> ConsulClient.builder().consulApiVersion("/v1"));
        ConsulClient.builder().consulApiVersion("v1");
    }
}
