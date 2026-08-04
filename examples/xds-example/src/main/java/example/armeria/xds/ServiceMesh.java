package example.armeria.xds;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.armeria.internal.common.util.SignedCertificate;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

final class ServiceMesh implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ServiceMesh.class);

    static final List<String> ZONES = List.of("us-east", "us-west", "eu-west");

    private final Path configDir;
    private final Map<String, String> certVars;
    private final Map<String, List<Integer>> layer2Ports;
    private final Map<String, List<Integer>> layer3Ports;
    private final List<Layer3Server> layer3Servers = new ArrayList<>();
    private final List<Layer2Server> layer2Servers = new ArrayList<>();
    private final ClientFactory layer1ClientFactory;
    private final ClientFactory layer2ClientFactory;
    private final XdsBootstrap layer1Bootstrap;
    private final XdsHttpPreprocessor layer1Preprocessor;
    private final WebClient client;
    private final PrometheusMeterRegistry meterRegistry;

    ServiceMesh(String initialProfile) {
        this(initialProfile, new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    ServiceMesh(String initialProfile, PrometheusMeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Metrics.globalRegistry.add(meterRegistry);
        final ProfileState initialState = new ProfileState(initialProfile);

        try {
            configDir = Files.createTempDirectory("armeria-xds-example");
            logger.info("xDS config directory: {}", configDir);

            // Generate a root CA, server cert, and client cert
            final SelfSignedCertificate rootCa = new SelfSignedCertificate();
            final SignedCertificate serverCert = new SignedCertificate("127.0.0.1", rootCa, false);
            final SignedCertificate clientCert = new SignedCertificate("127.0.0.1", rootCa, false);
            certVars = Map.of(
                    "server-cert-path", serverCert.certificate().getAbsolutePath(),
                    "server-key-path", serverCert.privateKey().getAbsolutePath(),
                    "client-cert-path", clientCert.certificate().getAbsolutePath(),
                    "client-key-path", clientCert.privateKey().getAbsolutePath(),
                    "ca-cert-path", rootCa.certificate().getAbsolutePath());
        } catch (Exception e) {
            Exceptions.throwUnsafely(e);
            throw new AssertionError(); // Never reaches here.
        }

        // 1. Write Layer 3 xDS config and start backend servers
        XdsTemplateReader.applyProfileForLayer(configDir, initialState, buildAllVars(), 3);

        for (String zone : ZONES) {
            for (int i = 0; i < 3; i++) {
                final Layer3Server server = new Layer3Server(zone, configDir);
                server.start();
                layer3Servers.add(server);
            }
        }
        layer3Ports = collectPorts(layer3Servers, Layer3Server::zone, Layer3Server::port);
        logger.info("Layer 3 backends started (9 servers) — {}", layer3Ports);

        // 2. Write Layer 2 xDS config and start middle-tier servers
        XdsTemplateReader.applyProfileForLayer(configDir, initialState, buildAllVars(), 2);

        layer2ClientFactory = newClientFactory("layer2.client");

        for (String zone : ZONES) {
            for (int i = 0; i < 3; i++) {
                final Layer2Server server = new Layer2Server(zone, configDir, layer2ClientFactory);
                server.start();
                layer2Servers.add(server);
            }
        }
        layer2Ports = collectPorts(layer2Servers, Layer2Server::zone, Layer2Server::port);
        logger.info("Layer 2 servers started (9 servers) — {}", layer2Ports);

        // 3. Write Layer 1 xDS config
        XdsTemplateReader.applyProfileForLayer(configDir, initialState, buildAllVars(), 1);

        // 4. Create Layer 1 xDS client
        layer1ClientFactory = newClientFactory("layer1.client");

        layer1Bootstrap = XdsBootstrap.builder(
                XdsTemplateReader.readBootstrap("layer1-bootstrap", Map.of(
                        "config-dir", configDir.toString())))
                .meterIdPrefix(new MeterIdPrefix("armeria.xds", "node", "layer1-dashboard"))
                .build();
        layer1Preprocessor =
                XdsHttpPreprocessor.ofListener("layer1-client-listener", layer1Bootstrap);

        client = WebClient.builder(layer1Preprocessor)
                          .factory(layer1ClientFactory)
                          .decorator(MetricCollectingClient.newDecorator(
                                  MeterIdPrefixFunction.ofDefault("layer1")))
                          .decorator(RouteMetricsClient.newDecorator(meterRegistry))
                          .build();
    }

    WebClient client() {
        return client;
    }

    Path configDir() {
        return configDir;
    }

    PrometheusMeterRegistry meterRegistry() {
        return meterRegistry;
    }

    Map<String, String> buildAllVars() {
        final Map<String, String> vars = new HashMap<>(certVars);
        vars.put("config-dir", configDir.toString());
        if (layer2Ports != null) {
            vars.putAll(XdsTemplateReader.portVars("layer2-", layer2Ports));
        }
        if (layer3Ports != null) {
            vars.putAll(XdsTemplateReader.portVars("layer3-", layer3Ports));
        }
        return vars;
    }

    void applyProfile(ProfileState state) {
        XdsTemplateReader.applyProfile(configDir, state, buildAllVars());
    }

    private static ClientFactory newClientFactory(String metricsPrefix) {
        return ClientFactory.builder()
                            .maxConnectionAge(Duration.ofSeconds(5))
                            .connectionPoolListener(ConnectionPoolListener.metricCollecting(
                                    Metrics.globalRegistry, new MeterIdPrefix(metricsPrefix)))
                            .build();
    }

    private static <T> Map<String, List<Integer>> collectPorts(
            List<T> servers, Function<T, String> zone, ToIntFunction<T> port) {
        final Map<String, List<Integer>> ports = new LinkedHashMap<>();
        for (T server : servers) {
            ports.computeIfAbsent(zone.apply(server), k -> new ArrayList<>()).add(port.applyAsInt(server));
        }
        return ports;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        layer1Preprocessor.close();
        layer1Bootstrap.close();
        layer1ClientFactory.close();
        layer2ClientFactory.close();
        final List<CompletableFuture<?>> futures = new ArrayList<>();
        for (Layer2Server server : layer2Servers) {
            futures.add(server.closeAsync());
        }
        for (Layer3Server server : layer3Servers) {
            futures.add(server.closeAsync());
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                                .thenRun(meterRegistry::close);
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
