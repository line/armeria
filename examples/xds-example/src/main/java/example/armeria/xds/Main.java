package example.armeria.xds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService;

/**
 * Starts a {@link ServiceMesh} and a dashboard server on port 8080.
 * The dashboard lets you send traffic through the mesh and switch
 * between configuration profiles at runtime.
 *
 * @see ServiceMesh
 */
public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final String INITIAL_PROFILE = "basic";

    public static void main(String[] args) {
        final ServiceMesh mesh = new ServiceMesh(INITIAL_PROFILE);

        final DashboardService dashboardService =
                new DashboardService(mesh, INITIAL_PROFILE);
        final Server dashboard = Server.builder()
                                       .http(8080)
                                       .service("/",
                                                HttpFile.of(Main.class.getClassLoader(), "index.html")
                                                        .asService())
                                       .annotatedService("/api", dashboardService)
                                       .service("/metrics",
                                                PrometheusExpositionService.of(
                                                        mesh.meterRegistry().getPrometheusRegistry()))
                                       .build();
        dashboard.closeOnJvmShutdown();
        Runtime.getRuntime().addShutdownHook(new Thread(mesh::close));
        dashboard.start().join();
        logger.info("Dashboard started at http://127.0.0.1:{}", dashboard.activeLocalPort());
        logger.info("Run Grafana with: docker compose up");
        logger.info("  (from the armeria repo: docker compose -f examples/xds-example/docker-compose.yml up)");
        logger.info("Grafana dashboard at http://localhost:3000");
        logger.info("Open your browser and visit the dashboard to interact with the xDS example.");
    }

    private Main() {}
}
