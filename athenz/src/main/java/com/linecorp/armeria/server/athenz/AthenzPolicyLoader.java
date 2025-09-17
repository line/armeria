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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.server.athenz.AthenzPolicyHandler.toAssertions;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableMap;
import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.athenz.zts.DomainSignedPolicyData;
import com.yahoo.athenz.zts.JWSPolicyData;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncLoader;

final class AthenzPolicyLoader {

    private final WebClient client;
    private final String targetDomain;
    private final AthenzPolicyConfig updaterConfig;
    @Nullable
    private final Map<String, Object> jwsPolicyParams;
    private final AthenzPolicyHandler policyHandler;
    private final AsyncLoader<AthenzAssertions> policyLoader;
    private final CompletableFuture<AthenzAssertions> initialPolicyData;

    AthenzPolicyLoader(ZtsBaseClient baseClient, String targetDomain,
                       AthenzPolicyConfig updaterConfig, PublicKeyStore publicKeyStore) {
        client = baseClient.webClient();
        this.targetDomain = targetDomain;
        this.updaterConfig = updaterConfig;
        if (updaterConfig.jwsPolicySupport()) {
            jwsPolicyParams = ImmutableMap.of(
                    "policyVersions", updaterConfig.policyVersions(),
                    "signatureP1363Format", true);
        } else {
            jwsPolicyParams = null;
        }

        policyHandler = new AthenzPolicyHandler(publicKeyStore);
        policyLoader = AsyncLoader.<AthenzAssertions>builder(unused -> loadPolicyData())
                                  .name("athenz-policy-loader/" + targetDomain)
                                  .refreshAfterLoad(updaterConfig.refreshInterval())
                                  .build();
        initialPolicyData = policyLoader.load();
    }

    void init() throws Exception {
        initialPolicyData.get(20, TimeUnit.SECONDS);
    }

    AthenzAssertions getNow() {
        checkState(initialPolicyData.isDone(), "Policy data is not initialized yet");
        return policyLoader.load().join();
    }

    private CompletableFuture<AthenzAssertions> loadPolicyData() {
        if (updaterConfig.jwsPolicySupport()) {
            return loadJwsPolicyData();
        } else {
            return loadSignedPolicyData();
        }
    }

    private CompletableFuture<AthenzAssertions> loadJwsPolicyData() {
        assert jwsPolicyParams != null;
        return client.prepare()
                     .post("/domain/{domain}/policy/signed")
                     .pathParam("domain", targetDomain)
                     .contentJson(jwsPolicyParams)
                     .asJson(JWSPolicyData.class)
                     .execute()
                     .thenApplyAsync(entity -> {
                         return toAssertions(policyHandler.getJWSPolicyData(entity.content()));
                     }, CommonPools.blockingTaskExecutor());
    }

    private CompletableFuture<AthenzAssertions> loadSignedPolicyData() {
        return client.prepare()
                     .get("/domain/{domain}/signed_policy_data")
                     .pathParam("domain", targetDomain)
                     .asJson(DomainSignedPolicyData.class)
                     .execute()
                     .thenApplyAsync(entity -> {
                         return toAssertions(policyHandler.getSignedPolicyData(entity.content()));
                     }, CommonPools.blockingTaskExecutor());
    }
}
