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

import static com.linecorp.armeria.server.athenz.AthenzDocker.ADMIN_POLICY;
import static com.linecorp.armeria.server.athenz.AthenzDocker.ADMIN_ROLE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.rdl.Struct;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;

@EnabledIfDockerAvailable
class AthenzPolicyLoaderTest {

    @RegisterExtension
    static AthenzExtension athenzExtension = new AthenzExtension();

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void loadPolicyFiles(boolean jwsPolicySupport) throws Exception {
        try (ZtsBaseClient baseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final PublicKeyStore publicKeyStore = new AthenzPublicKeyProvider(baseClient,
                                                                              Duration.ofSeconds(10),
                                                                              "/oauth2/keys?rfc=true");
            final AthenzPolicyConfig policyConfig = new AthenzPolicyConfig(ImmutableList.of(TEST_DOMAIN_NAME),
                                                                           ImmutableMap.of(), jwsPolicySupport,
                                                                           Duration.ofSeconds(10));

            final AthenzPolicyLoader policyLoader = new AthenzPolicyLoader(baseClient, TEST_DOMAIN_NAME,
                                                                           policyConfig, publicKeyStore);

            assertThatThrownBy(policyLoader::getNow)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Policy data is not initialized yet");
            policyLoader.init();
            final AthenzAssertions assertions = policyLoader.getNow();
            final List<Struct> adminPolicy = assertions.roleStandardAllowMap().get(ADMIN_ROLE);
            assertThat(adminPolicy).satisfiesOnlyOnce(struct -> {
                assertThat(struct.get("polname")).isEqualTo(TEST_DOMAIN_NAME + ":policy." + ADMIN_POLICY);
            });
        }
    }
}
