/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.kubernetes.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.kubernetes.ArmeriaHttpClient;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

final class ChaosMeshAvailableCondition {

    private static final Logger logger = LoggerFactory.getLogger(ChaosMeshAvailableCondition.class);

    static boolean isRunning() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            assertThat(client.getHttpClient()).isInstanceOf(ArmeriaHttpClient.class);
            final Namespace namespace = client.namespaces().withName("chaos-mesh").get();
            return "Active".equals(namespace.getStatus().getPhase());
        } catch (Exception cause) {
            logger.trace("Chaos Mesh is not running in Kubernetes", cause);
            return false;
        }
    }

    private ChaosMeshAvailableCondition() {}
}
