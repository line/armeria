package example.armeria.grpc;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import example.armeria.grpc.Hello.HelloReply;
import example.armeria.grpc.Hello.HelloRequest;
import org.testcontainers.junit.jupiter.Container;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcReverseProxyServerTest {

	private static Server server;

	@Container
	private static GenericContainer<?> envoy = new GenericContainer<>("envoyproxy/envoy:v1.22.0")
			.withExposedPorts(9090)
			.withFileSystemBind("./envoy/envoy.yaml", "/etc/envoy/envoy.yaml", BindMode.READ_WRITE);


	@BeforeAll
	static void startServer() {
		server = Server.builder()
				.http(8080)
				.service(GrpcService.builder()
						.addService(new HelloServiceImpl())
						.build())
				.build();
		server.start().join();
		envoy.start();

	}

	@AfterAll
	static void stopServer() {
		server.stop().join();
		envoy.stop();
	}

	@Test
	void reverseProxy() {

		// given
		String envoyAddress = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(9090);

		WebClient client = WebClient.of(envoyAddress);
		HelloServiceGrpc.HelloServiceBlockingStub helloService = GrpcClients.builder(client.uri())
				.build(HelloServiceGrpc.HelloServiceBlockingStub.class);

		// when
		HelloReply reply = helloService.hello(HelloRequest.newBuilder().setName("Armeria").build());

		// then
		assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
	}

}
