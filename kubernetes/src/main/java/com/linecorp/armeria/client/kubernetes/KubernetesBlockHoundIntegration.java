/*
 *  Copyright 2024 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.kubernetes;

import com.linecorp.armeria.common.annotation.UnstableApi;

import reactor.blockhound.BlockHound.Builder;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * A {@link BlockHoundIntegration} for the Fabric Kubernetes module.
 */
@UnstableApi
public final class KubernetesBlockHoundIntegration implements BlockHoundIntegration {
    @Override
    public void applyTo(Builder builder) {
        // KubernetesClient uses a ReentrantLock. The lock is not acquired for a long time because we create
        // a fully readable ByteBuffer.
        builder.allowBlockingCallsInside(
                "io.fabric8.kubernetes.client.http.HttpClientReadableByteChannel", "doLockedAndSignal");
        // StandardHttpRequest creates UUIDs using java.util.UUID.randomUUID() that uses SecureRandom.
        // The method is temporarily allowed until the problem is resolved in the upstream.
        // See: https://github.com/fabric8io/kubernetes-client/issues/5735
        // TODO(ikhoon): Remove this once the issue is fixed.
        builder.allowBlockingCallsInside(
                "io.fabric8.kubernetes.client.http.StandardHttpRequest$Builder",
                "build");
    }
}
