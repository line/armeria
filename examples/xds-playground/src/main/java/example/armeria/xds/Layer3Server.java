package example.armeria.xds;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

final class Layer3Server implements AsyncCloseable {

    private final String nodeId;
    private final String zone;
    private final Path configDir;
    private Server server;
    private XdsBootstrap xdsBootstrap;

    Layer3Server(String zone, Path configDir) {
        nodeId = "layer3-" + zone;
        this.zone = zone;
        this.configDir = configDir;
    }

    void start() {
        xdsBootstrap = XdsBootstrap.builder(
                XdsTemplateReader.readBootstrap("layer3-bootstrap", Map.of(
                        "config-dir", configDir.toString())))
                .meterIdPrefix(new MeterIdPrefix("armeria.xds", "node", nodeId))
                .build();

        server = Server.builder()
                       .plugin(XdsServerPlugin.of(xdsBootstrap, "layer3-server-listener"))
                       .service("/", (ctx, req) ->
                               HttpResponse.of(HttpStatus.OK)
                                           .mapHeaders(h -> h.toBuilder()
                                                             .add("layer3-node", nodeId)
                                                             .build()))
                       .decorator(MetricCollectingService.newDecorator(
                               MeterIdPrefixFunction.ofDefault(nodeId)))
                       .build();
        server.start().join();
    }

    String zone() {
        return zone;
    }

    int port() {
        return server.activePort().localAddress().getPort();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        xdsBootstrap.close();
        return server.stop();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
