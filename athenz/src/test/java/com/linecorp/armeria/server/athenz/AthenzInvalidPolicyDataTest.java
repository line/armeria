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

import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

@EnabledIfDockerAvailable
class AthenzInvalidPolicyDataTest {

    @RegisterExtension
    static final AthenzExtension athenzExtension = new AthenzExtension();

    @Test
    void shouldFailWithInvalidPolicyData() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE, cb -> {
            cb.decorator((delegate, ctx, req) -> {
                if (ctx.path().equals("/zts/v1/domain/" + TEST_DOMAIN_NAME + "/policy/signed")) {
                    // Simulate an invalid policy data response.
                    return HttpResponse.of(HttpStatus.OK,
                                           MediaType.JSON_UTF_8,
                                           "{\"name\":\"invalid_policy\",\"data\":\"invalid_data\"}");
                } else {
                    return delegate.execute(ctx, req);
                }
            });
        })) {
            assertThatThrownBy(() -> {
                AthenzService.builder(ztsBaseClient)
                             .action("obtain")
                             .resource("secrets")
                             .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                             .buildAuthZpeClient();
            }).isInstanceOf(IllegalStateException.class)
              .hasRootCauseInstanceOf(AthenzPolicyException.class)
              .hasMessageContaining("ZTS signature validation failed");
        }
    }
}
