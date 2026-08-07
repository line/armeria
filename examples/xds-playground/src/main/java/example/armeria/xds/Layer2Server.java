package example.armeria.xds;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

final class Layer2Server implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Layer2Server.class);

    private final String nodeId;
    private final Path configDir;
    private final String zone;
    private final ClientFactory clientFactory;
    private Server server;
    // Normally we would use a single bootstrap, but this is separated for simplicity
    // to have a single file per resource
    private XdsBootstrap clientBootstrap;
    private XdsBootstrap serverBootstrap;
    private XdsHttpPreprocessor preprocessor;

    Layer2Server(String zone, Path configDir, ClientFactory clientFactory) {
        nodeId = "layer2-" + zone;
        this.configDir = configDir;
        this.zone = zone;
        this.clientFactory = clientFactory;
    }

    void start() {
        final Map<String, String> bootstrapVars = Map.of(
                "config-dir", configDir.toString(), "zone", zone);
        clientBootstrap = XdsBootstrap.builder(
                XdsTemplateReader.readBootstrap("layer2-client-bootstrap", bootstrapVars))
                .meterIdPrefix(new MeterIdPrefix("armeria.xds", "node", nodeId))
                .build();
        serverBootstrap = XdsBootstrap.builder(
                XdsTemplateReader.readBootstrap("layer2-server-bootstrap", bootstrapVars))
                .meterIdPrefix(new MeterIdPrefix("armeria.xds", "node", nodeId + "-server"))
                .build();
        preprocessor = XdsHttpPreprocessor.ofListener("layer2-client-listener", clientBootstrap);

        final WebClient xdsClient = WebClient.builder(preprocessor)
                                           .factory(clientFactory)
                                           .decorator(MetricCollectingClient.newDecorator(
                                                   MeterIdPrefixFunction.ofDefault("layer2")
                                                                        .withTags("node", nodeId,
                                                                                  "side", "client")))
                                           .build();

        server = Server.builder()
                       .plugin(XdsServerPlugin.of(serverBootstrap, "layer2-server-listener"))
                       .service("/", (ctx, req) -> {
                               ctx.addAdditionalResponseHeader("layer2-node", nodeId);
                               return xdsClient.execute(req);
                           })
                       .decorator(MetricCollectingService.newDecorator(
                               MeterIdPrefixFunction.ofDefault("layer2")
                                                    .withTags("node", nodeId,
                                                              "side", "server")))
                       .build();
        server.start().join();
        logger.info("Layer 2 server ({}) started on port {}", zone, port());
    }

    String zone() {
        return zone;
    }

    int port() {
        return server.activePort().localAddress().getPort();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        preprocessor.close();
        clientBootstrap.close();
        serverBootstrap.close();
        return server.stop();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
