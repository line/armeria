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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;

class ConsulClientBuilderTest extends ConsulTestBase {

    @Test
    void gets403WhenNoToken() throws Exception {
        final HttpStatus status = WebClient.of("http://localhost:" + consul().getHttpPort())
                                           .get("/v1/agent/self").aggregate()
                                           .get().status();
        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void consulApiVersionCanNotStartsWithSlash() {
        assertThrows(IllegalArgumentException.class, () ->
                ConsulClient.builder(URI.create("http://localhost:8500")).consulApiVersion("/v1"));
        ConsulClient.builder(URI.create("http://localhost:8500")).consulApiVersion("v1");
    }
}
