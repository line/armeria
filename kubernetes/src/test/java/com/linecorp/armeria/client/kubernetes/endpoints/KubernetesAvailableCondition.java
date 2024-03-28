/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes.endpoints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

@SuppressWarnings("unused")
final class KubernetesAvailableCondition {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesAvailableCondition.class);

    static boolean isRunning() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            client.namespaces().list();
            return true;
        } catch (Exception cause) {
            logger.trace("Kubernetes is not running", cause);
            return false;
        }
    }

    private KubernetesAvailableCondition() {}
}
