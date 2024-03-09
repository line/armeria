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
import static com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupBuilder.DEFAULT_NAMESPACE;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.jctools.maps.NonBlockingHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;

/**
 * A {@link DynamicEndpointGroup} that fetches node IPs and ports for each Pod from Kubernetes.
 */
public final class KubernetesEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesEndpointGroup.class);

    public static KubernetesEndpointGroup of(KubernetesClient kubernetesClient, String namespace,
                                             String serviceName) {
        return builder(kubernetesClient)
                .namespace(namespace)
                .serviceName(serviceName)
                .build();
    }

    public static KubernetesEndpointGroup ofDefault(String serviceName) {
        return of(DEFAULT_NAMESPACE, serviceName);
    }

    public static KubernetesEndpointGroup of(String namespace, String serviceName) {
        return builder(new KubernetesClientBuilder().build())
                .namespace(namespace)
                .serviceName(serviceName)
                .build();
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroupBuilder} with the specified {@link KubernetesClient}.
     */
    public static KubernetesEndpointGroupBuilder builder(KubernetesClient kubernetesClient) {
        return new KubernetesEndpointGroupBuilder(kubernetesClient);
    }

    private final KubernetesClient client;
    private final String namespace;
    private final String serviceName;

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

    KubernetesEndpointGroup(KubernetesClient client, String namespace, String serviceName,
                            EndpointSelectionStrategy selectionStrategy, boolean allowEmptyEndpoints,
                            long selectionTimeoutMillis) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        this.client = client;
        this.namespace = namespace;
        this.serviceName = serviceName;
        nodeWatch = watchNodes();
        serviceWatch = watchService();
    }

    /**
     * Watches the service. {@link Watcher} will retry automatically on failures.
     */
    private Watch watchService() {
        return client.services().inNamespace(namespace).withName(serviceName).watch(new Watcher<Service>() {
            @Override
            public void eventReceived(Action action, Service service) {
                if (closed) {
                    return;
                }

                switch (action) {
                    case ADDED:
                    case MODIFIED:
                        final List<ServicePort> ports = service.getSpec().getPorts();
                        if (ports.isEmpty()) {
                            logger.warn("No ports in the service: {}", service);
                            return;
                        }
                        nodePort = ports.get(0).getNodePort();
                        KubernetesEndpointGroup.this.service = service;

                        Watch podWatch0 = podWatch;
                        if (podWatch0 != null) {
                            podWatch0.close();
                        }
                        podWatch0 = watchPod(service.getSpec().getSelector());
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
        });
    }

    private Watch watchPod(Map<String, String> selector) {
        return client.pods().inNamespace(namespace).withLabels(selector).watch(new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod resource) {
                if (closed) {
                    if (podWatch != null) {
                        podWatch.close();
                    }
                    return;
                }
                if (action == Action.ERROR || action == Action.BOOKMARK) {
                    return;
                }
                final String podName = resource.getMetadata().getName();
                final String nodeName = resource.getSpec().getNodeName();
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
        });
    }

    /**
     * Fetches the internal IPs of the node.
     */
    private Watch watchNodes() {
        return client.nodes().watch(new Watcher<Node>() {
            @Override
            public void eventReceived(Action action, Node node) {
                if (closed) {
                    return;
                }

                if (action == Action.ERROR || action == Action.BOOKMARK) {
                    return;
                }

                final String nodeName = node.getMetadata().getName();
                final String nodeIp = node.getStatus().getAddresses().stream()
                                          .filter(address -> "InternalIP".equals(address.getType()))
                                          .map(NodeAddress::getAddress)
                                          .findFirst().orElse(null);

                switch (action) {
                    case ADDED:
                    case MODIFIED:
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
        });
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
        client.close();
        super.doCloseAsync(future);
    }
}
