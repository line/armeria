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
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;

import org.jctools.maps.NonBlockingHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.WatcherException;

/**
 * A {@link DynamicEndpointGroup} that discovers endpoints from Kubernetes pods.
 *
 * <p>Two endpoint discovery modes are supported via {@link KubernetesEndpointMode}:
 * <ul>
 *   <li>{@link KubernetesEndpointMode#NODE_PORT} (default) — uses {@code nodeIP:nodePort} for endpoints.
 *       The Kubernetes service must have a type of
 *       <a href="https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport">NodePort</a>
 *       or <a href="https://kubernetes.io/docs/concepts/services-networking/service/#loadbalancer">LoadBalancer</a>
 *       to expose a node port. This mode requires RBAC permissions for {@code pods}, {@code services},
 *       and {@code nodes}.</li>
 *   <li>{@link KubernetesEndpointMode#POD} — uses {@code podIP:containerPort} for endpoints, enabling
 *       true client-side load balancing by connecting directly to pod IPs, bypassing kube-proxy.
 *       This mode is intended for clients running inside the Kubernetes cluster and only requires
 *       RBAC permissions for {@code pods} and {@code services}.</li>
 * </ul>
 *
 * <p>{@link KubernetesEndpointGroup} watches the services and pods (and nodes in
 * {@link KubernetesEndpointMode#NODE_PORT} mode) in the Kubernetes cluster and updates the endpoints,
 * so the credentials in the {@link Config} used to create {@link KubernetesClient}
 * should have the appropriate permissions. Otherwise, the {@link KubernetesEndpointGroup} will not be
 * able to fetch the endpoints.
 *
 * <p>For instance, the following <a href="https://kubernetes.io/docs/reference/access-authn-authz/rbac/#referring-to-subjects">RBAC</a>
 * configuration is required for {@link KubernetesEndpointMode#NODE_PORT} mode:
 * <pre>{@code
 * apiVersion: rbac.authorization.k8s.io/v1
 * kind: ClusterRole
 * metadata:
 *   name: my-cluster-role
 * rules:
 * - apiGroups: [""]
 *   resources: ["pods", "services", "nodes"]
 *   verbs: ["get", "list", "watch"]
 * }</pre>
 *
 * <p>For {@link KubernetesEndpointMode#POD} mode, only {@code pods} and {@code services} are required:
 * <pre>{@code
 * apiVersion: rbac.authorization.k8s.io/v1
 * kind: ClusterRole
 * metadata:
 *   name: my-cluster-role
 * rules:
 * - apiGroups: [""]
 *   resources: ["pods", "services"]
 *   verbs: ["get", "list", "watch"]
 * }</pre>
 *
 * <p>Example:
 * <pre>{@code
 * // NODE_PORT mode (default): uses nodeIP:nodePort
 * KubernetesClient kubernetesClient = new KubernetesClientBuilder().build();
 * KubernetesEndpointGroup
 *   .builder(kubernetesClient)
 *   .namespace("default")
 *   .serviceName("my-service")
 *   .build();
 *
 * // POD mode: uses podIP:containerPort for true client-side load balancing
 * KubernetesEndpointGroup
 *   .builder(kubernetesClient)
 *   .namespace("default")
 *   .serviceName("my-service")
 *   .mode(KubernetesEndpointMode.POD)
 *   .portName("http")
 *   .build();
 * }</pre>
 */
@UnstableApi
public final class KubernetesEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesEndpointGroup.class);

    private static final KubernetesClient DEFAULT_CLIENT = new KubernetesClientBuilder().build();

    private static final AtomicIntegerFieldUpdater<KubernetesEndpointGroup> wipUpdater =
            AtomicIntegerFieldUpdater.newUpdater(KubernetesEndpointGroup.class, "wip");

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
    @Nullable
    private final Function<Node, @Nullable String> nodeIpExtractor;
    private final long maxWatchAgeMillis;

    @Nullable
    private volatile Watch nodeWatch;
    @Nullable
    private volatile Watch serviceWatch;
    @Nullable
    private volatile Watch podWatch;

    private final KubernetesEndpointMode mode;

    // NODE_PORT mode maps
    private final Map<String, String> podToNode = new NonBlockingHashMap<>();
    private final Map<String, String> nodeToIp = new NonBlockingHashMap<>();
    @Nullable
    private volatile Integer nodePort;

    // POD mode map
    private final Map<String, Endpoint> podToEndpoint = new NonBlockingHashMap<>();

    @Nullable
    private volatile Service service;

    private final ReentrantShortLock schedulerLock = new ReentrantShortLock();
    @GuardedBy("schedulerLock")
    @Nullable
    private ScheduledFuture<?> scheduledFuture;

    // Used to prevent the old watchers from updating endpoints.
    private final ReentrantShortLock updateLock = new ReentrantShortLock();
    private long updateId;

    // Used for serializing the start() method.
    private volatile int wip;
    private volatile boolean closed;
    private volatile int numStartFailures;
    private volatile int numServiceFailures;
    private volatile int numNodeFailures;
    private volatile int numPodFailures;

    KubernetesEndpointGroup(KubernetesClient client, @Nullable String namespace, String serviceName,
                            @Nullable String portName,
                            @Nullable Function<Node, @Nullable String> nodeIpExtractor,
                            boolean autoClose, KubernetesEndpointMode mode,
                            EndpointSelectionStrategy selectionStrategy,
                            boolean allowEmptyEndpoints, long selectionTimeoutMillis, long maxWatchAgeMillis) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        this.client = client;
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.portName = portName;
        this.nodeIpExtractor = nodeIpExtractor;
        this.autoClose = autoClose;
        this.mode = mode;
        this.maxWatchAgeMillis = maxWatchAgeMillis == Long.MAX_VALUE ? 0 : maxWatchAgeMillis;
        executeJob(() -> start(true));
    }

    private void start(boolean initial) {
        if (wipUpdater.getAndIncrement(this) > 0) {
            // Another thread is already starting.
            return;
        }

        do {
            if (closed) {
                return;
            }
            if (!doStart(initial)) {
                wipUpdater.set(this, 0);
                return;
            }
            initial = false;
            // Repeat until all `start()` requests are handled.
            // Even if `doStart()` returns true, `start()` can be called recursively. Although `watchXXX()`
            // successfully opens WebSocket sessions, if `Watcher.onClose()` is called quickly, a retry request
            // may come in before the `start()` function finishes.
        } while (wipUpdater.decrementAndGet(this) > 0);
    }

    private boolean doStart(boolean initial) {
        closeResources();
        updateLock.lock();
        try {
            // Change the updateId to ensure that outdated watchers do not update endpoints.
            updateId++;
            nodeToIp.clear();
            podToNode.clear();
            podToEndpoint.clear();
        } finally {
            updateLock.unlock();
        }

        final Service service;
        final PodList pods;
        try {
            logger.info("[{}/{}] Fetching the service...", namespace, serviceName);
            if (namespace == null) {
                service = client.services().withName(serviceName).get();
            } else {
                service = client.services().inNamespace(namespace).withName(serviceName).get();
            }
            if (service == null) {
                logger.warn("[{}/{}] Service not found.", namespace, serviceName);
                throw new IllegalStateException(
                        String.format("[%s/%s] Service not found.", namespace, serviceName));
            }
            if (!updateService(service)) {
                throw new IllegalStateException(
                        String.format("[%s/%s] NodePort not found.", namespace, serviceName));
            }

            final Map<String, String> selector = service.getSpec().getSelector();
            switch (mode) {
                case NODE_PORT:
                    logger.info("[{}/{}] Fetching the nodes ...", namespace, serviceName);
                    final NodeList nodes = client.nodes().list();
                    for (Node node : nodes.getItems()) {
                        updateNode(Action.ADDED, node);
                    }
                    // Node watcher is started after pod fetching below.
                    // Save the resource version for the node watcher.
                    final String nodeResourceVersion = nodes.getMetadata().getResourceVersion();

                    logger.info("[{}/{}] Fetching the pods with the selector: {}",
                                namespace, serviceName, selector);
                    if (namespace == null) {
                        pods = client.pods().withLabels(selector).list();
                    } else {
                        pods = client.pods().inNamespace(namespace).withLabels(selector).list();
                    }
                    for (Pod pod : pods.getItems()) {
                        updatePod(Action.ADDED, pod);
                    }
                    maybeUpdateEndpoints();

                    watchService(service.getMetadata().getResourceVersion());
                    watchNode(updateId, nodeResourceVersion);
                    watchPod(updateId, pods.getMetadata().getResourceVersion());
                    break;
                case POD:
                    // POD mode: skip node fetch/watch
                    logger.info("[{}/{}] Fetching the pods with the selector: {}",
                                namespace, serviceName, selector);
                    if (namespace == null) {
                        pods = client.pods().withLabels(selector).list();
                    } else {
                        pods = client.pods().inNamespace(namespace).withLabels(selector).list();
                    }
                    for (Pod pod : pods.getItems()) {
                        updatePod(Action.ADDED, pod);
                    }
                    maybeUpdateEndpoints();

                    watchService(service.getMetadata().getResourceVersion());
                    watchPod(updateId, pods.getMetadata().getResourceVersion());
                    break;
                default:
                    throw new Error("unknown mode: " + mode);
            }
        } catch (Exception e) {
            logger.warn("[{}/{}] Failed to start {}. (initial: {})", namespace, serviceName, this, initial, e);
            if (initial) {
                failInit(e);
                // Do not retry if the initialization fails since the error is likely to be persistent.
                return false;
            } else {
                scheduleRestartWithBackoff(++numStartFailures);
                return true;
            }
        }

        if (closed) {
            closeResources();
            return false;
        }
        if (maxWatchAgeMillis > 0) {
            scheduleRestart(maxWatchAgeMillis);
        }
        numStartFailures = 0;
        return true;
    }

    private void watchService(String resourceVersion) {
        logger.info("[{}/{}] Start the service watcher... (resource version: {})", namespace, serviceName,
                    resourceVersion);
        serviceWatch = doWatchService(resourceVersion);
        logger.info("[{}/{}] Service watcher is started.", namespace, serviceName);
    }

    /**
     * Watches the service. {@link Watcher} will retry automatically on failures by {@link KubernetesClient}.
     */
    private Watch doWatchService(String resourceVersion) {
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
                        if (!service0.getMetadata().getResourceVersion().equals(resourceVersion)) {
                            // Rebuild all resources if the service has been updated.
                            scheduleRestart(0);
                        }
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

                // Immediately retry on the first failure.
                scheduleRestartWithBackoff(++numServiceFailures);
            }

            @Override
            public void onClose() {
                logger.info("[{}/{}] Service watcher is closed gracefully.", namespace, serviceName);
            }
        };

        final Service service = this.service;
        assert service != null;

        // Fabric8 Kubernetes client uses a list API to establish a watch connection, such as
        // "/api/v1/namespaces/<namespace>/services?fieldSelector=metadata.name=<service-name>&watch=true".
        // Therefore, the resource version of a service cannot be used. Instead, it fetches the latest version
        // and compares it with the cached version in `watcher.eventReceived()` to decide whether an update is
        // needed.
        if (namespace == null) {
            return client.services().withName(serviceName).watch(watcher);
        } else {
            return client.services().inNamespace(namespace).withName(serviceName).watch(watcher);
        }
    }

    private boolean updateService(Service service) {
        this.service = service;

        if (mode == KubernetesEndpointMode.POD) {
            // In POD mode, we only need the service for its selector. No nodePort needed.
            return true;
        }

        final List<ServicePort> ports = service.getSpec().getPorts();
        final Integer nodePort0 =
                ports.stream()
                     .filter(p -> portName == null || portName.equals(p.getName()))
                     .map(ServicePort::getNodePort)
                     .filter(Objects::nonNull)
                     .findFirst().orElse(null);
        if (nodePort0 == null) {
            if (portName != null) {
                logger.warn("No node port matching '{}' in the service: {}", portName,
                            service);
            } else {
                logger.warn(
                        "No node port in the service. Either 'NodePort' or 'LoadBalancer' " +
                        "should be set as the type for your Kubernetes service to expose " +
                        "a node port. type:{}, service:{}", service.getSpec().getType(),
                        service);
            }
            return false;
        }
        nodePort = nodePort0;
        return true;
    }

    private void watchPod(long updateId, String resourceVersion) {
        logger.info("[{}/{}] Start the pod watcher... (resource version: {})", namespace, serviceName,
                    resourceVersion);
        podWatch = doWatchPod(updateId, resourceVersion);
        logger.info("[{}/{}] Pod watcher is started.", namespace, serviceName);
    }

    private Watch doWatchPod(long updateId, String resourceVersion) {
        final Watcher<Pod> watcher = new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod resource) {
                if (closed) {
                    return;
                }
                numPodFailures = 0;
                withUpdateLock(updateId, () -> {
                    if (!updatePod(action, resource)) {
                        return;
                    }
                    maybeUpdateEndpoints();
                });
            }

            @Override
            public void onClose(WatcherException cause) {
                if (closed) {
                    return;
                }

                logger.warn("[{}/{}] Pod watcher is closed.", namespace, serviceName, cause);
                scheduleRestartWithBackoff(++numPodFailures);
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
            return client.pods().withLabels(selector).withResourceVersion(resourceVersion).watch(watcher);
        } else {
            return client.pods().inNamespace(namespace).withLabels(selector)
                         .withResourceVersion(resourceVersion).watch(watcher);
        }
    }

    private boolean updatePod(Action action, Pod resource) {
        if (action == Action.ERROR || action == Action.BOOKMARK) {
            return false;
        }
        final String podName = resource.getMetadata().getName();
        if (podName == null) {
            logger.debug("[{}/{}] Pod name is null.", namespace, serviceName);
            return false;
        }

        switch (mode) {
            case NODE_PORT:
                return updatePodForNodePortMode(action, podName, resource);
            case POD:
                return updatePodForPodMode(action, podName, resource);
            default:
                throw new Error("unknown mode: " + mode);
        }
    }

    private boolean updatePodForNodePortMode(Action action, String podName, Pod resource) {
        final String nodeName = resource.getSpec().getNodeName();
        logger.debug("[{}/{}] Pod event received. action: {}, pod: {}, node: {}, resource version: {}",
                     namespace, serviceName, action, podName, nodeName,
                     resource.getMetadata().getResourceVersion());

        if (nodeName == null) {
            logger.debug("[{}/{}] Node name is null. pod: {}", namespace, serviceName, podName);
            return false;
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
        return true;
    }

    private boolean updatePodForPodMode(Action action, String podName, Pod resource) {
        logger.debug("[{}/{}] Pod event received (POD mode). action: {}, pod: {}, resource version: {}",
                     namespace, serviceName, action, podName,
                     resource.getMetadata().getResourceVersion());

        switch (action) {
            case ADDED:
            case MODIFIED:
                final Endpoint endpoint = extractPodEndpoint(resource);
                if (endpoint != null) {
                    podToEndpoint.put(podName, endpoint);
                } else {
                    podToEndpoint.remove(podName);
                }
                break;
            case DELETED:
                podToEndpoint.remove(podName);
                break;
            default:
        }
        return true;
    }

    @Nullable
    private Endpoint extractPodEndpoint(Pod pod) {
        final String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
        if (podIp == null) {
            // Pod is likely in a pending state. This is expected and not an error.
            return null;
        }
        final Integer containerPort = findContainerPort(pod);
        if (containerPort == null) {
            logger.warn("[{}/{}] No matching container port found in pod: {}",
                        namespace, serviceName, pod.getMetadata().getName());
            return null;
        }
        return Endpoint.of(podIp, containerPort);
    }

    @Nullable
    private Integer findContainerPort(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return null;
        }
        for (Container container : pod.getSpec().getContainers()) {
            if (container.getPorts() == null) {
                continue;
            }
            for (ContainerPort port : container.getPorts()) {
                if (portName == null) {
                    // If no portName is specified, return the first container port.
                    return port.getContainerPort();
                }
                if (portName.equals(port.getName())) {
                    return port.getContainerPort();
                }
            }
        }
        return null;
    }

    private void watchNode(long updateId, String resourceVersion) {
        logger.info("[{}/{}] Start the node watcher... (resource version: {})", namespace, serviceName,
                    resourceVersion);
        nodeWatch = doWatchNode(updateId, resourceVersion);
        logger.info("[{}/{}] Node watcher is started.", namespace, serviceName);
    }

    /**
     * Fetches the internal IPs of the node.
     */
    private Watch doWatchNode(long updateId, String resourceVersion) {
        final Watcher<Node> watcher = new Watcher<Node>() {
            @Override
            public void eventReceived(Action action, Node node) {
                if (closed) {
                    return;
                }
                numNodeFailures = 0;
                withUpdateLock(updateId, () -> {
                    if (!updateNode(action, node)) {
                        return;
                    }
                    maybeUpdateEndpoints();
                });
            }

            @Override
            public void onClose(WatcherException cause) {
                if (closed) {
                    return;
                }
                logger.warn("[{}/{}] Node watcher is closed.", namespace, serviceName, cause);
                scheduleRestartWithBackoff(++numNodeFailures);
            }

            @Override
            public void onClose() {
                logger.info("[{}/{}] Node watcher is closed gracefully.", namespace, serviceName);
            }
        };

        return client.nodes().withResourceVersion(resourceVersion).watch(watcher);
    }

    private boolean updateNode(Action action, Node node) {
        if (action == Action.ERROR || action == Action.BOOKMARK) {
            return false;
        }

        final String nodeName = node.getMetadata().getName();
        logger.debug("[{}/{}] Node event received. action: {}, node: {}, resource version: {}",
                     namespace, serviceName, action, nodeName, node.getMetadata().getResourceVersion());
        switch (action) {
            case ADDED:
            case MODIFIED:
                final String nodeIp;
                try {
                    nodeIp = nodeIpExtractor.apply(node);
                } catch (Throwable ex) {
                    logger.warn("[{}/{}] Failed to extract the IP address of the node: {}",
                                namespace, serviceName, nodeName, ex);
                    nodeToIp.remove(nodeName);
                    return true;
                }

                if (nodeIp == null) {
                    logger.debug("[{}/{}] No matching IP address is found in {}. node: {}",
                                 namespace, serviceName, nodeName, node);
                    nodeToIp.remove(nodeName);
                } else {
                    nodeToIp.put(nodeName, nodeIp);
                }
                break;
            case DELETED:
                nodeToIp.remove(nodeName);
                break;
        }
        return true;
    }

    private void executeJob(Runnable job) {
        worker.execute(safeRunnable(job));
    }

    private ScheduledFuture<?> scheduleJob(Runnable job, long delayMillis) {
        return worker.schedule(safeRunnable(job), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRestartWithBackoff(int numFailures) {
        final long delayMillis = delayMillis(numFailures);
        logger.info("[{}/{}] Reconnecting to the Kubernetes API in {} ms (numFailures: {})",
                    namespace, serviceName, delayMillis, numFailures);
        scheduleRestart(delayMillis);
    }

    private void scheduleRestart(long delayMillis) {
        schedulerLock.lock();
        try {
            final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }

            if (closed) {
                return;
            }

            if (delayMillis == 0) {
                executeJob(() -> start(false));
                return;
            }
            this.scheduledFuture = scheduleJob(() -> start(false), delayMillis);
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

    private static long delayMillis(int numFailures) {
        if (numFailures == 1) {
            // Retry immediately on the first failure.
            return 0;
        }
        return Backoff.ofDefault().nextDelayMillis(numFailures - 1);
    }

    private void maybeUpdateEndpoints() {
        if (closed) {
            return;
        }
        if (service == null) {
            // No event received for the service yet.
            return;
        }

        final List<Endpoint> endpoints;
        switch (mode) {
            case NODE_PORT:
                if (nodeToIp.isEmpty()) {
                    // No event received for the nodes yet.
                    return;
                }

                if (podToNode.isEmpty()) {
                    // No event received for the pods yet.
                    return;
                }

                final Integer nodePort = this.nodePort;
                assert nodePort != null;
                endpoints =
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
                break;
            case POD:
                endpoints = ImmutableList.copyOf(podToEndpoint.values());
                break;
            default:
                throw new Error("unknown mode: " + mode);
        }
        setEndpoints(endpoints);
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        closed = true;
        closeResources();
        if (autoClose) {
            client.close();
        }
        super.doCloseAsync(future);
    }

    private void closeResources() {
        schedulerLock.lock();
        try {
            final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
        } finally {
            schedulerLock.unlock();
        }

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
    }

    private void withUpdateLock(long updateId, Runnable task) {
        updateLock.lock();
        try {
            if (this.updateId != updateId) {
                // The current update is outdated.
                return;
            }
            task.run();
        } finally {
            updateLock.unlock();
        }
    }
}
