package example.armeria.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static example.armeria.athenz.Main.configureAthenz;
import static example.armeria.athenz.Main.configureServices;
import static example.armeria.athenz.Main.newAthenzDocker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.athenz.AthenzDocker;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.grpc.Hello.HelloReply;
import example.armeria.grpc.Hello.HelloRequest;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceBlockingStub;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;

@EnabledIfDockerAvailable
class AthenzTest {

    private static final AthenzDocker athenzDocker = newAthenzDocker();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            assumeThat(athenzDocker.initialize()).isTrue();
            configureServices(sb);
            configureAthenz(sb, athenzDocker.ztsUri());
        }
    };

    private static ZtsBaseClient ztsBaseClient;

    @BeforeAll
    static void beforeAll() {
        final String tenantKeyFile = "gen-src/main/resources/docker/certs/foo-service/key.pem";
        final String tenantCertFile = "gen-src/main/resources/docker/certs/foo-service/cert.pem";
        final String caCertFile = "gen-src/main/resources/docker/certs/CAs/athenz_ca_cert.pem";
        ztsBaseClient = ZtsBaseClient.builder(athenzDocker.ztsUri())
                                     .keyPair(tenantKeyFile, tenantCertFile)
                                     // caCertFile may not be necessary in production,
                                     // but it is required for testing.
                                     .trustedCertificate(caCertFile)
                                     .build();
    }

    @AfterAll
    static void afterAll() {
        ztsBaseClient.close();
        athenzDocker.close();
    }

    @Test
    void rest_shouldRejectUnauthorizedRequest() {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .build()
                         .blocking();
        final AggregatedHttpResponse response0 = client.get("/rest/hello/Armeria");
        assertThat(response0.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rest_shouldAccessResource() {
        final BlockingWebClient clientWithAthenz =
                WebClient.builder(server.httpUri())
                         // Use the AthenzClient to obtain an access token for the specified role.
                         .decorator(AthenzClient.newDecorator(ztsBaseClient,
                                                              TEST_DOMAIN_NAME,
                                                              "test_role",
                                                              TokenType.ACCESS_TOKEN))
                         .build()
                         .blocking();

        final AggregatedHttpResponse response1 = clientWithAthenz.get("/rest/hello/Armeria");
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void grpc_shouldRejectUnauthorizedRequest() {
        final HelloServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .pathPrefix("/grpc")
                           .build(HelloServiceBlockingStub.class);

        assertThatThrownBy(() -> {
            client.hello(HelloRequest.newBuilder()
                                     .setName("Armeria")
                                     .build());
        }).isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
            assertThat(cause.getStatus().getCode()).isEqualTo(Code.UNAUTHENTICATED);
        });
    }

    @Test
    void grpc_shouldAccessResource() {
        final HelloServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .pathPrefix("/grpc")
                            .decorator(AthenzClient.newDecorator(ztsBaseClient,
                                                                 TEST_DOMAIN_NAME,
                                                                 "test_role",
                                                                 TokenType.ACCESS_TOKEN))
                           .build(HelloServiceBlockingStub.class);

        final HelloReply response = client.hello(HelloRequest.newBuilder()
                                                             .setName("Armeria")
                                                             .build());
        assertThat(response.getMessage()).isEqualTo("Hello, Armeria!");
    }
}
