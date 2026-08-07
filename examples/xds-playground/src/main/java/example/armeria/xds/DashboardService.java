package example.armeria.xds;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Any;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.armeria.xds.XdsType;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;

@Blocking
final class DashboardService {

    private static final List<String> AVAILABLE_PROFILES =
            XdsTemplateReader.VALID_PROFILES.values().stream()
                                            .flatMap(List::stream).distinct().sorted().toList();

    private final ServiceMesh mesh;
    private final BlockingWebClient layer1Client;
    private volatile ProfileState profileState;

    DashboardService(ServiceMesh mesh, String initialPreset) {
        this.mesh = mesh;
        layer1Client = mesh.client().blocking();
        profileState = new ProfileState(initialPreset);
    }

    // -- Request/Response DTOs --
    record SendTrafficRequest(Map<String, String> headers) {}

    record XdsResourceInfo(String file, String type, String name, String config) {}

    record ConfigResponse(String configDir, List<XdsResourceInfo> resources) {}

    record ResourceEntry(String type, String config) {}

    record UpdateFileRequest(String file, List<ResourceEntry> resources) {}

    record ProfilesResponse(String baseProfile, Map<String, String> overrides,
                            List<String> available,
                            Map<String, List<String>> validProfiles) {}

    record PreviewRequest(String resource, String type, String profile) {}

    // -- Endpoints --
    @Post("/send-traffic")
    public HttpResponse sendTraffic(@RequestObject SendTrafficRequest body) {
        final Map<String, String> headers = body.headers();
        final RequestHeadersBuilder reqBuilder =
                RequestHeaders.builder(HttpMethod.GET, "/");
        if (headers != null) {
            headers.forEach(reqBuilder::add);
        }
        layer1Client.execute(reqBuilder.build());
        return HttpResponse.of(HttpStatus.OK);
    }

    @Get("/config")
    public HttpResponse getConfig() throws IOException {
        final List<XdsResourceInfo> resources = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mesh.configDir(), "*.yaml")) {
            for (Path path : stream) {
                resources.addAll(readResources(path));
            }
        }

        return HttpResponse.ofJson(new ConfigResponse(mesh.configDir().toString(), resources));
    }

    private static List<XdsResourceInfo> readResources(Path path)
            throws InvalidProtocolBufferException {
        final String fileName = path.getFileName().toString();
        final var response = XdsTemplateReader.readDiscoveryResponse(path);
        final String typeUrl = response.getTypeUrl();
        final List<XdsResourceInfo> results = new ArrayList<>();
        for (Any any : response.getResourcesList()) {
            if (XdsType.LISTENER.typeUrl().equals(typeUrl)) {
                final Listener listener = any.unpack(Listener.class);
                results.add(new XdsResourceInfo(
                        fileName, "listener", listener.getName(),
                        XdsTemplateReader.printProtoYaml(listener)));
            } else if (XdsType.CLUSTER.typeUrl().equals(typeUrl)) {
                final Cluster cluster = any.unpack(Cluster.class);
                results.add(new XdsResourceInfo(
                        fileName, "cluster", cluster.getName(),
                        XdsTemplateReader.printProtoYaml(cluster)));
            } else if (XdsType.ENDPOINT.typeUrl().equals(typeUrl)) {
                final ClusterLoadAssignment endpoints =
                        any.unpack(ClusterLoadAssignment.class);
                results.add(new XdsResourceInfo(
                        fileName, "endpoint", endpoints.getClusterName(),
                        XdsTemplateReader.printProtoYaml(endpoints)));
            }
        }
        return results;
    }

    @Post("/update-file")
    public HttpResponse updateFile(@RequestObject UpdateFileRequest body) {
        final String file = body.file();
        if (file == null || file.isEmpty() || body.resources() == null || body.resources().isEmpty()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }

        final Path path = mesh.configDir().resolve(file).normalize();
        if (!path.startsWith(mesh.configDir().normalize())) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }
        final String firstType = body.resources().get(0).type();
        if (firstType == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }
        final String typeUrl;
        final List<GeneratedMessageV3> resources = new ArrayList<>();
        switch (firstType) {
            case "listener" -> {
                typeUrl = XdsType.LISTENER.typeUrl();
                for (ResourceEntry entry : body.resources()) {
                    resources.add(XdsResourceReader.from(entry.config(), Listener.class));
                }
            }
            case "cluster" -> {
                typeUrl = XdsType.CLUSTER.typeUrl();
                for (ResourceEntry entry : body.resources()) {
                    resources.add(XdsResourceReader.from(entry.config(), Cluster.class));
                }
            }
            case "endpoint" -> {
                typeUrl = XdsType.ENDPOINT.typeUrl();
                for (ResourceEntry entry : body.resources()) {
                    resources.add(XdsResourceReader.from(entry.config(), ClusterLoadAssignment.class));
                }
            }
            default -> {
                return HttpResponse.of(HttpStatus.BAD_REQUEST);
            }
        }
        XdsTemplateReader.writeDiscoveryResponse(path, typeUrl, resources);
        return HttpResponse.of(HttpStatus.OK);
    }

    @Post("/preview")
    public HttpResponse preview(@RequestObject PreviewRequest body) {
        final String resource = body.resource();
        final String type = body.type();
        if (resource == null || type == null || body.profile() == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }
        final ProfileState state = new ProfileState(body.profile());
        final Map<String, String> allVars = mesh.buildAllVars();
        final GeneratedMessageV3 rendered = switch (type) {
            case "listener" -> XdsTemplateReader.readListener(state, resource, allVars);
            case "cluster" -> XdsTemplateReader.read(state, resource, allVars, Cluster.class);
            case "endpoint" -> XdsTemplateReader.read(state, resource, allVars, ClusterLoadAssignment.class);
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
        return HttpResponse.ofJson(new XdsResourceInfo(
                "", type, resource, XdsTemplateReader.printProtoYaml(rendered)));
    }

    @Get("/profiles")
    public HttpResponse getProfiles() {
        return HttpResponse.ofJson(new ProfilesResponse(
                profileState.baseProfile(), profileState.overrides(),
                AVAILABLE_PROFILES, XdsTemplateReader.VALID_PROFILES));
    }

    @Post("/apply-profile")
    public HttpResponse applyProfile(@RequestObject ProfileState body) {
        profileState = profileState.mergeFrom(body);
        mesh.applyProfile(profileState);
        return HttpResponse.of(HttpStatus.OK);
    }
}
