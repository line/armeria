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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.jctools.maps.NonBlockingHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.ShutdownHooks;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;

/**
 * A {@link DynamicEndpointGroup} that fetches a node IP and a node port for each Pod from Kubernetes.
 *
 * <p>Note that the Kubernetes service must have a type of <a href="https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport">NodePort</a>
 * or <a href="https://kubernetes.io/docs/concepts/services-networking/service/#loadbalancer">'LoadBalancer'</a>
 * to expose a node port for client side load balancing.
 *
 * <p>{@link KubernetesEndpointGroup} watches the nodes, services and pods in the Kubernetes cluster and updates
 * the endpoints, so the credentials in the {@link Config} used to create {@link KubernetesClient} should
 * have permission to watch {@code services}, {@code nodes} and {@code pods}. Otherwise, the
 * {@link KubernetesEndpointGroup} will not be able to fetch the endpoints.
 *
 * <p>For instance, the following <a href="https://kubernetes.io/docs/reference/access-authn-authz/rbac/#referring-to-subjects">RBAC</a>
 * configuration is required:
 * <pre>{@code
 * apiVersion: rbac.authorization.k8s.io/v1
 * kind: ClusterRole
 * metadata:
 *   name: my-cluster-role
 * rules:
 * - apiGroups: [""]
 *   resources: ["pods", "services", "nodes"]
 *   verbs: ["watch"]
 * }</pre>
 *
 * <p>Example:
 * <pre>{@code
 * // Create a KubernetesEndpointGroup that fetches the endpoints of the 'my-service' service in the 'default'
 * // namespace. The Kubernetes client will be created with the default configuration in the $HOME/.kube/config.
 * KubernetesClient kubernetesClient = new KubernetesClientBuilder().build();
 * KubernetesEndpointGroup
 *   .builder(kubernetesClient)
 *   .namespace("default")
 *   .serviceName("my-service")
 *   .build();
 *
 * // If you want to use a custom configuration, you can create a KubernetesEndpointGroup as follows:
 * // The custom configuration would be useful when you want to access Kubernetes from outside the cluster.
 * Config config =
 *   new ConfigBuilder()
 *     .withMasterUrl("https://my-k8s-master")
 *     .withOauthToken("my-token")
 *     .build();
 * KubernetesEndpointGroup
 *   .builder(config)
 *   .namespace("my-namespace")
 *   .serviceName("my-service")
 *   .build();
 * }</pre>
 */
@UnstableApi
public final class KubernetesEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesEndpointGroup.class);

    private static final KubernetesClient DEFAULT_CLIENT = new KubernetesClientBuilder().build();

    static {
        ShutdownHooks.addClosingTask(DEFAULT_CLIENT);
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroup} with the specified {@link KubernetesClient},
     * {@code namespace} and {@code serviceName}.
     *
     * <p>Note that the {@link KubernetesClient} will not be automatically closed when the
     * {@link KubernetesEndpointGroup} is closed.
     */
    public static KubernetesEndpointGroup of(KubernetesClient kubernetesClient, String namespace,
                                             String serviceName) {
        return builder(kubernetesClient)
                .namespace(namespace)
                .serviceName(serviceName)
                .build();
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroup} with the specified {@link Config},
     * {@code namespace} and {@code serviceName}.
     */
    public static KubernetesEndpointGroup of(Config config, String namespace, String serviceName) {
        requireNonNull(config, "config");
        return builder(new KubernetesClientBuilder().withConfig(config).build(), true)
                .namespace(namespace)
                .serviceName(serviceName)
                .build();
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroup} with the specified {@code serviceName}.
     * The default configuration in the {@code $HOME/.kube/config} will be used to create a
     * {@link KubernetesClient}.
     */
    public static KubernetesEndpointGroup of(String serviceName) {
        return builder(DEFAULT_CLIENT, false)
                .serviceName(serviceName)
                .build();
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroup} with the specified {@code namespace} and
     * {@code serviceName}.
     * The default configuration in the $HOME/.kube/config will be used to create a {@link KubernetesClient}.
     */
    public static KubernetesEndpointGroup of(String namespace, String serviceName) {
        return builder(DEFAULT_CLIENT, false)
                .namespace(namespace)
                .serviceName(serviceName)
                .build();
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroupBuilder} with the specified
     * {@link KubernetesClient}.
     *
     * <p>Note that the {@link KubernetesClient} will not be automatically closed when the
     * {@link KubernetesEndpointGroup} is closed.
     */
    public static KubernetesEndpointGroupBuilder builder(KubernetesClient kubernetesClient) {
        return new KubernetesEndpointGroupBuilder(kubernetesClient, false);
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroupBuilder} with the specified
     * {@link KubernetesClient}.
     *
     * @param autoClose whether to close the {@link KubernetesClient} when the {@link KubernetesEndpointGroup}
     *                  is closed.
     */
    public static KubernetesEndpointGroupBuilder builder(KubernetesClient kubernetesClient, boolean autoClose) {
        return new KubernetesEndpointGroupBuilder(kubernetesClient, autoClose);
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroupBuilder} with the specified Kubernetes
     * {@link Config}.
     */
    public static KubernetesEndpointGroupBuilder builder(Config kubeConfig) {
        requireNonNull(kubeConfig, "kubeConfig");
        return builder(new KubernetesClientBuilder().withConfig(kubeConfig).build(), true);
    }

    private final KubernetesClient client;
    private final boolean autoClose;
    @Nullable
    private final String namespace;
    private final String serviceName;
    @Nullable
    private final String portName;
    private final Predicate<? super NodeAddress> nodeAddressFilter;

    private final Watch nodeWatch;
    private final Watch serviceWatch;
    @Nullable
    private volatile Watch podWatch;

    private final Map<String, String> podToNode = new NonBlockingHashMap<>();
    private final Map<String, String> nodeToIp = new NonBlockingHashMap<>();
    @Nullable
    private Service service;
    @Nullable
    private Integer nodePort;

    private volatile boolean closed;

    KubernetesEndpointGroup(KubernetesClient client, @Nullable String namespace, String serviceName,
                            @Nullable String portName, Predicate<? super NodeAddress> nodeAddressFilter,
                            boolean autoClose, EndpointSelectionStrategy selectionStrategy,
                            boolean allowEmptyEndpoints, long selectionTimeoutMillis) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        this.client = client;
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.portName = portName;
        this.nodeAddressFilter = nodeAddressFilter;
        this.autoClose = autoClose;
        nodeWatch = watchNodes();
        serviceWatch = watchService();
    }

    /**
     * Watches the service. {@link Watcher} will retry automatically on failures by {@link KubernetesClient}.
     */
    private Watch watchService() {
        final Watcher<Service> watcher = new Watcher<Service>() {
            @Override
            public void eventReceived(Action action, Service service0) {
                if (closed) {
                    return;
                }

                switch (action) {
                    case ADDED:
                    case MODIFIED:
                        final List<ServicePort> ports = service0.getSpec().getPorts();
                        final Integer nodePort0 =
                                ports.stream()
                                     .filter(p -> portName == null || portName.equals(p.getName()))
                                     .map(ServicePort::getNodePort)
                                     .filter(Objects::nonNull)
                                     .findFirst().orElse(null);
                        if (nodePort0 == null) {
                            if (portName != null) {
                                logger.warn("No node port matching '{}' in the service: {}", portName,
                                            service0);
                            } else {
                                logger.warn(
                                        "No node port in the service. Either 'NodePort' or 'LoadBalancer' " +
                                        "should be set as the type for your Kubernetes service to expose " +
                                        "a node port. type:{}, service:{}", service0.getSpec().getType(),
                                        service0);
                            }
                            return;
                        }
                        service = service0;
                        nodePort = nodePort0;

                        Watch podWatch0 = podWatch;
                        if (podWatch0 != null) {
                            podWatch0.close();
                        }
                        podWatch0 = watchPod(service0.getSpec().getSelector());
                        if (closed) {
                            podWatch0.close();
                        } else {
                            podWatch = podWatch0;
                        }
                        break;
                    case DELETED:
                        logger.warn("{} service is deleted. (namespace: {})", serviceName, namespace);
                        // This situation should not occur in production.
                        break;
                    case ERROR:
                    case BOOKMARK:
                        // Do nothing.
                        break;
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (closed) {
                    return;
                }
                logger.warn("{} service watcher is closed. (namespace: {})", namespace, serviceName, cause);
            }
        };

        if (namespace == null) {
            return client.services().withName(serviceName).watch(watcher);
        } else {
            return client.services().inNamespace(namespace).withName(serviceName).watch(watcher);
        }
    }

    private Watch watchPod(Map<String, String> selector) {
        final Watcher<Pod> watcher = new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod resource) {
                if (closed) {
                    return;
                }
                if (action == Action.ERROR || action == Action.BOOKMARK) {
                    return;
                }
                final String podName = resource.getMetadata().getName();
                final String nodeName = resource.getSpec().getNodeName();
                if (podName == null || nodeName == null) {
                    logger.debug("Pod or node name is null. pod: {}, node: {}", podName, nodeName);
                    return;
                }

                switch (action) {
                    case ADDED:
                    case MODIFIED:
                        podToNode.put(podName, nodeName);
                        break;
                    case DELETED:
                        podToNode.remove(podName);
                        break;
                    default:
                }
                maybeUpdateEndpoints();
            }

            @Override
            public void onClose(WatcherException cause) {
                if (closed) {
                    return;
                }

                logger.warn("Pod watcher for {}/{} is closed.", namespace, serviceName, cause);
            }
        };

        if (namespace == null) {
            return client.pods().withLabels(selector).watch(watcher);
        } else {
            return client.pods().inNamespace(namespace).withLabels(selector).watch(watcher);
        }
    }

    /**
     * Fetches the internal IPs of the node.
     */
    private Watch watchNodes() {
        final Watcher<Node> watcher = new Watcher<Node>() {
            @Override
            public void eventReceived(Action action, Node node) {
                if (closed) {
                    return;
                }

                if (action == Action.ERROR || action == Action.BOOKMARK) {
                    return;
                }

                final String nodeName = node.getMetadata().getName();
                switch (action) {
                    case ADDED:
                    case MODIFIED:
                        final String nodeIp = node.getStatus().getAddresses().stream()
                                                  .filter(nodeAddressFilter)
                                                  .map(NodeAddress::getAddress)
                                                  .findFirst().orElse(null);
                        if (nodeIp == null) {
                            logger.debug("No matching IP address is found in {}. node: {}", nodeName, node);
                            nodeToIp.remove(nodeName);
                            return;
                        }
                        nodeToIp.put(nodeName, nodeIp);
                        break;
                    case DELETED:
                        nodeToIp.remove(nodeName);
                        break;
                }
                maybeUpdateEndpoints();
            }

            @Override
            public void onClose(WatcherException cause) {
                if (closed) {
                    return;
                }
                logger.warn("Node watcher for {}/{} is closed.", namespace, serviceName, cause);
            }
        };

        return client.nodes().watch(watcher);
    }

    private void maybeUpdateEndpoints() {
        if (service == null) {
            // No event received for the service yet.
            return;
        }

        if (nodeToIp.isEmpty()) {
            // No event received for the nodes yet.
            return;
        }

        if (podToNode.isEmpty()) {
            // No event received for the pods yet.
            return;
        }

        assert nodePort != null;
        final List<Endpoint> endpoints =
                podToNode.values().stream()
                         .map(nodeName -> {
                             final String nodeIp = nodeToIp.get(nodeName);
                             if (nodeIp == null) {
                                 return null;
                             }
                             return Endpoint.of(nodeIp, nodePort);
                         })
                         .filter(Objects::nonNull)
                         .collect(toImmutableList());
        setEndpoints(endpoints);
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        closed = true;
        serviceWatch.close();
        nodeWatch.close();
        final Watch podWatch = this.podWatch;
        if (podWatch != null) {
            podWatch.close();
        }
        if (autoClose) {
            client.close();
        }
        super.doCloseAsync(future);
    }
}
