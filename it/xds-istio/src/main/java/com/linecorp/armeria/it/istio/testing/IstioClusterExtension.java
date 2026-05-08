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
package com.linecorp.armeria.it.istio.testing;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * A JUnit extension that manages Kubernetes cluster lifecycle with Istio for testing.
 *
 * <p>Default behavior:
 * <ul>
 *   <li>Reuses an existing Kind container if one is running and kubeconfig is available</li>
 *   <li>Otherwise, creates a new Kind container</li>
 *   <li>Istio is always installed on the cluster</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * class MyIstioTest {
 *     @RegisterExtension
 *     static IstioClusterExtension istio = new IstioClusterExtension();
 *
 *     @Test
 *     void test() {
 *         KubernetesClient client = istio.client();
 *         // Test logic
 *     }
 * }
 * }</pre>
 *
 * <p>For Istio reinstallation on each test:
 * <pre>{@code
 * @RegisterExtension
 * static IstioClusterExtension istio = IstioClusterExtension.builder()
 *     .runForEachTest(true)
 *     .build();
 * }</pre>
 */
public final class IstioClusterExtension extends HostOnlyExtension {

    private static final Logger logger = LoggerFactory.getLogger(IstioClusterExtension.class);

    public static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(IstioClusterExtension.class);
    public static final String K8S_CLIENT_KEY = "kubernetesClient";

    @Nullable
    private IstioState state;

    private final boolean runForEachTest;

    /**
     * Creates a new instance with default settings.
     */
    public IstioClusterExtension() {
        this(builder());
    }

    private IstioClusterExtension(Builder builder) {
        runForEachTest = builder.runForEachTest;
    }

    @Override
    protected boolean runForEachTest() {
        return runForEachTest;
    }

    /**
     * Returns a new builder for configuring the extension.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    void setUp(ExtensionContext context) throws Exception {
        state = IstioState.connectOrCreate();
        context.getStore(NAMESPACE).put(K8S_CLIENT_KEY, state.client());
    }

    @Override
    void tearDown(ExtensionContext context) throws Exception {
        if (state != null) {
            try {
                state.client().namespaces().withLabel("test-namespace", "true").delete();
            } catch (Exception e) {
                logger.warn("Failed to clean up test namespaces", e);
            }
            state.close();
            state = null;
        }
    }

    /**
     * Returns the Kubernetes client for interacting with the cluster.
     */
    public KubernetesClient client() {
        if (state == null) {
            throw new IllegalStateException("Kubernetes client not initialized. " +
                                            "Ensure the extension is properly registered.");
        }
        return state.client();
    }

    /**
     * Blocks until Istiod is ready in the cluster, or returns {@code false} on timeout.
     */
    public boolean waitForIstiodReady() {
        return IstioInstaller.waitForIstiodReady(client());
    }

    /**
     * Builder for configuring {@link IstioClusterExtension}.
     */
    public static final class Builder {
        private boolean runForEachTest;

        private Builder() {}

        /**
         * Sets whether to run the extension for each test method.
         * When true, Istio will be reinstalled before each test.
         * Default is false.
         */
        public Builder runForEachTest(boolean runForEachTest) {
            this.runForEachTest = runForEachTest;
            return this;
        }

        /**
         * Builds the configured {@link IstioClusterExtension}.
         */
        public IstioClusterExtension build() {
            return new IstioClusterExtension(this);
        }
    }
}
