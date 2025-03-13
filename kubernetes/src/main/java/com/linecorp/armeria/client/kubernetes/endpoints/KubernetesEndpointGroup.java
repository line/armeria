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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import org.jctools.maps.NonBlockingHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.ShutdownHooks;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

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

    /**
     * The debounce millis for the update of the endpoints.
     * A short delay would be enough because the initial events are delivered sequentially.
     */
    private static final int DEBOUNCE_MILLIS = 100;

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

    // TODO(ikhoon): Consider a dedicated executor for the blocking tasks if necessary.
    private final ScheduledExecutorService worker = CommonPools.blockingTaskExecutor();

    private final KubernetesClient client;
    private final boolean autoClose;
    @Nullable
    private final String namespace;
    private final String serviceName;
    @Nullable
    private final String portName;
    private final Predicate<? super NodeAddress> nodeAddressFilter;
    private final long maxWatchAgeMillis;

    // watchLock may acquire the lock for a long time, so we need to use a separate lock.
    @SuppressWarnings("PreferReentrantShortLock")
    private final ReentrantLock watchLock = new ReentrantLock();
    @Nullable
    private volatile Watch nodeWatch;
    @Nullable
    private volatile Watch serviceWatch;
    @Nullable
    private volatile Watch podWatch;

    private final Map<String, String> podToNode = new NonBlockingHashMap<>();
    private final Map<String, String> nodeToIp = new NonBlockingHashMap<>();
    @Nullable
    private volatile Service service;
    @Nullable
    private volatile Integer nodePort;

    private final ReentrantShortLock schedulerLock = new ReentrantShortLock();
    @GuardedBy("schedulerLock")
    @Nullable
    private ScheduledFuture<?> updateScheduledFuture;
    @GuardedBy("schedulerLock")
    @Nullable
    private ScheduledFuture<?> serviceScheduledFuture;
    @GuardedBy("schedulerLock")
    @Nullable
    private ScheduledFuture<?> nodeScheduledFuture;
    @GuardedBy("schedulerLock")
    @Nullable
    private ScheduledFuture<?> podScheduledFuture;

    private volatile boolean closed;
    private volatile int numServiceFailures;
    private volatile int numNodeFailures;
    private volatile int numPodFailures;

    KubernetesEndpointGroup(KubernetesClient client, @Nullable String namespace, String serviceName,
                            @Nullable String portName, Predicate<? super NodeAddress> nodeAddressFilter,
                            boolean autoClose, EndpointSelectionStrategy selectionStrategy,
                            boolean allowEmptyEndpoints, long selectionTimeoutMillis, long maxWatchAgeMillis) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        this.client = client;
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.portName = portName;
        this.nodeAddressFilter = nodeAddressFilter;
        this.autoClose = autoClose;
        this.maxWatchAgeMillis = maxWatchAgeMillis == Long.MAX_VALUE ? 0 : maxWatchAgeMillis;
        watchJob(this::watchNode);
        watchJob(this::watchService);
    }

    private void watchService() {
        watchLock.lock();
        try {
            final Watch oldServiceWatch = serviceWatch;
            if (oldServiceWatch != null) {
                oldServiceWatch.close();
            }
            if (closed) {
                return;
            }
            final Watch newServiceWatch;
            logger.info("[{}/{}] Start the service watcher...", namespace, serviceName);
            try {
                newServiceWatch = doWatchService();
            } catch (Exception e) {
                logger.warn("[{}/{}] Failed to start the service watcher.", namespace, serviceName, e);
                return;
            }
            // Recheck the closed flag because the doWatchService() method may take a while.
            if (closed) {
                newServiceWatch.close();
                return;
            }
            serviceWatch = newServiceWatch;
            logger.info("[{}/{}] Service watcher is started.", namespace, serviceName);
        } finally {
            watchLock.unlock();
        }

        if (maxWatchAgeMillis > 0) {
            logger.debug("[{}/{}] Schedule a new Service watcher to start in {} ms.", namespace,
                         serviceName, maxWatchAgeMillis);
            scheduleWatchService(maxWatchAgeMillis);
        }
    }

    /**
     * Watches the service. {@link Watcher} will retry automatically on failures by {@link KubernetesClient}.
     */
    private Watch doWatchService() {
        final Watcher<Service> watcher = new Watcher<Service>() {
            @Override
            public void eventReceived(Action action, Service service0) {
                if (closed) {
                    return;
                }

                numServiceFailures = 0;
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

                        watchJob(() -> watchPod());
                        break;
                    case DELETED:
                        logger.warn("[{}/{}] service is deleted.", namespace, serviceName);
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
                // Note: As per the JavaDoc of Watcher.onClose(), the method should not be implemented in a
                //       blocking way.
                if (closed) {
                    return;
                }
                logger.warn("[{}/{}] Service watcher is closed.", namespace, serviceName, cause);
                logger.info("[{}/{}] Reconnecting the service watcher...", namespace, serviceName);

                // Immediately retry on the first failure.
                final long delayMillis = delayMillis(numServiceFailures++);
                scheduleWatchService(delayMillis);
            }

            @Override
            public void onClose() {
                logger.info("[{}/{}] Service watcher is closed gracefully.", namespace, serviceName);
            }
        };

        if (namespace == null) {
            return client.services().withName(serviceName).watch(watcher);
        } else {
            return client.services().inNamespace(namespace).withName(serviceName).watch(watcher);
        }
    }

    private void watchPod() {
        watchLock.lock();
        try {
            final Watch oldPodWatch = podWatch;
            if (oldPodWatch != null) {
                oldPodWatch.close();
            }

            if (closed) {
                return;
            }
            final Watch newPodwatch;
            logger.info("[{}/{}] Start the pod watcher...", namespace, serviceName);
            try {
                newPodwatch = doWatchPod();
            } catch (Exception e) {
                logger.warn("[{}/{}] Failed to start the pod watcher.", namespace, serviceName, e);
                return;
            }
            // Recheck the closed flag because the doWatchPod() method may take a while.
            if (closed) {
                newPodwatch.close();
            } else {
                podWatch = newPodwatch;
                logger.info("[{}/{}] Pod watcher is started.", namespace, serviceName);
            }
        } finally {
            watchLock.unlock();
        }
    }

    private Watch doWatchPod() {
        // Clear the podToNode map before starting a new pod watch.
        podToNode.clear();
        final Watcher<Pod> watcher = new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod resource) {
                if (closed) {
                    return;
                }

                numPodFailures = 0;
                if (action == Action.ERROR || action == Action.BOOKMARK) {
                    return;
                }
                final String podName = resource.getMetadata().getName();
                final String nodeName = resource.getSpec().getNodeName();
                logger.debug("[{}/{}] Pod event received. action: {}, pod: {}, node: {}",
                             namespace, serviceName, action, podName, nodeName);

                if (podName == null || nodeName == null) {
                    logger.debug("[{}/{}] Pod or node name is null. pod: {}, node: {}",
                                 namespace, serviceName, podName, nodeName);
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
                maybeUpdateEndpoints(false);
            }

            @Override
            public void onClose(WatcherException cause) {
                if (closed) {
                    return;
                }

                logger.warn("[{}/{}] Pod watcher is closed.", namespace, serviceName, cause);
                logger.info("[{}/{}] Reconnecting the pod watcher...", namespace, serviceName);

                final long delayMillis = delayMillis(numPodFailures++);
                scheduleWatchPod(delayMillis);
            }

            @Override
            public void onClose() {
                logger.info("[{}/{}] Pod watcher is closed gracefully.", namespace, serviceName);
            }
        };

        final Service service = this.service;
        assert service != null;
        final Map<String, String> selector = service.getSpec().getSelector();
        // watch() method will block until the watch connection is established.
        if (namespace == null) {
            return client.pods().withLabels(selector).watch(watcher);
        } else {
            return client.pods().inNamespace(namespace).withLabels(selector).watch(watcher);
        }
    }

    private void watchNode() {
        watchLock.lock();
        try {
            final Watch oldNodeWatch = nodeWatch;
            if (oldNodeWatch != null) {
                oldNodeWatch.close();
            }

            if (closed) {
                return;
            }
            logger.info("[{}/{}] Start the node watcher...", namespace, serviceName);
            final Watch newNodeWatch = doWatchNode();
            // Recheck the closed flag because the doWatchNode() method may take a while.
            if (closed) {
                newNodeWatch.close();
                return;
            }
            nodeWatch = newNodeWatch;
            logger.info("[{}/{}] Node watcher is started.", namespace, serviceName);
        } finally {
            watchLock.unlock();
        }

        if (maxWatchAgeMillis > 0) {
            logger.debug("[{}/{}] Schedule a new Node watcher to start in {} ms.",
                         namespace, serviceName, maxWatchAgeMillis);
            scheduleWatchNode(maxWatchAgeMillis);
        }
    }

    /**
     * Fetches the internal IPs of the node.
     */
    private Watch doWatchNode() {
        nodeToIp.clear();
        final Watcher<Node> watcher = new Watcher<Node>() {
            @Override
            public void eventReceived(Action action, Node node) {
                if (closed) {
                    return;
                }

                numNodeFailures = 0;
                if (action == Action.ERROR || action == Action.BOOKMARK) {
                    return;
                }

                final String nodeName = node.getMetadata().getName();
                logger.debug("[{}/{}] Node event received. action: {}, node: {}",
                             namespace, serviceName, action, nodeName);
                switch (action) {
                    case ADDED:
                    case MODIFIED:
                        final String nodeIp = node.getStatus().getAddresses().stream()
                                                  .filter(nodeAddressFilter)
                                                  .map(NodeAddress::getAddress)
                                                  .findFirst().orElse(null);
                        if (nodeIp == null) {
                            logger.debug("[{}/{}] No matching IP address is found in {}. node: {}",
                                         namespace, serviceName, nodeName, node);
                            nodeToIp.remove(nodeName);
                            return;
                        }
                        nodeToIp.put(nodeName, nodeIp);
                        break;
                    case DELETED:
                        nodeToIp.remove(nodeName);
                        break;
                }
                maybeUpdateEndpoints(false);
            }

            @Override
            public void onClose(WatcherException cause) {
                if (closed) {
                    return;
                }
                logger.warn("[{}/{}] Node watcher is closed.", namespace, serviceName, cause);
                logger.info("[{}/{}] Reconnecting the node watcher...", namespace, serviceName);
                final long delayMillis = Backoff.ofDefault().nextDelayMillis(numNodeFailures++);
                scheduleWatchNode(delayMillis);
            }

            @Override
            public void onClose() {
                logger.info("[{}/{}] Node watcher is closed gracefully.", namespace, serviceName);
            }
        };

        return client.nodes().watch(watcher);
    }

    private void watchJob(Runnable job) {
        final Runnable safeRunnable = safeRunnable(job);
        worker.execute(safeRunnable);
    }

    private ScheduledFuture<?> scheduleJob(Runnable job, long delayMillis) {
        return worker.schedule(safeRunnable(job), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleWatchService(long delayMillis) {
        schedulerLock.lock();
        try {
            final ScheduledFuture<?> serviceScheduledFuture = this.serviceScheduledFuture;
            if (serviceScheduledFuture != null) {
                serviceScheduledFuture.cancel(false);
            }

            if (delayMillis == 0) {
                watchJob(this::watchService);
                return;
            }
            this.serviceScheduledFuture = scheduleJob(this::watchService, delayMillis);
        } finally {
            schedulerLock.unlock();
        }
    }

    private void scheduleWatchNode(long delayMillis) {
        schedulerLock.lock();
        try {
            final ScheduledFuture<?> nodeScheduledFuture = this.nodeScheduledFuture;
            if (nodeScheduledFuture != null) {
                nodeScheduledFuture.cancel(false);
            }
            if (delayMillis == 0) {
                watchJob(this::watchNode);
                return;
            }
            this.nodeScheduledFuture = scheduleJob(this::watchNode, delayMillis);
        } finally {
            schedulerLock.unlock();
        }
    }

    private void scheduleWatchPod(long delayMillis) {
        schedulerLock.lock();
        try {
            final ScheduledFuture<?> podScheduledFuture = this.podScheduledFuture;
            if (podScheduledFuture != null) {
                podScheduledFuture.cancel(false);
            }
            if (delayMillis == 0) {
                watchJob(this::watchPod);
                return;
            }
            this.podScheduledFuture = scheduleJob(this::watchPod, delayMillis);
        } finally {
            schedulerLock.unlock();
        }
    }

    private Runnable safeRunnable(Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (Exception e) {
                logger.warn("[{}/{}] Failed to run a watch job.", namespace, serviceName, e);
            }
        };
    }

    private static long delayMillis(int numAttempts) {
        if (numAttempts == 0) {
            return 0;
        }
        return Backoff.ofDefault().nextDelayMillis(numAttempts);
    }

    private void maybeUpdateEndpoints(boolean scheduledJob) {
        if (closed) {
            return;
        }
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

        schedulerLock.lock();
        try {
            if (scheduledJob) {
                updateScheduledFuture = null;
            } else {
                if (updateScheduledFuture != null) {
                    // A scheduled job is already scheduled.
                    return;
                }
                // Schedule a job to debounce the update of the endpoints.
                updateScheduledFuture = worker.schedule(() -> maybeUpdateEndpoints(true),
                                                        DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
                return;
            }
        } finally {
            schedulerLock.unlock();
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
        final Watch serviceWatch = this.serviceWatch;
        if (serviceWatch != null) {
            serviceWatch.close();
        }
        final Watch nodeWatch = this.nodeWatch;
        if (nodeWatch != null) {
            nodeWatch.close();
        }
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
