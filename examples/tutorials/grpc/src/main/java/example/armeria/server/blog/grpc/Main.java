package example.armeria.server.blog.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.grpc.GrpcService;

import example.armeria.server.blog.grpc.Blog.BlogPost;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;

final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();
            logger.info("Server has been stopped.");
        }));

        server.start().join();

        logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs",
                    server.activeLocalPort());
    }

    private static Server newServer(int port) throws Exception {
        final GrpcService grpcService =
                GrpcService.builder()
                           .addService(new BlogService())
                           .enableUnframedRequests(true)
                           // You can set useBlockingTaskExecutor(true) in order to execute all gRPC methods in
                           // the blockingTaskExecutor thread pool.
                           // .useBlockingTaskExecutor(true)
                           .build();

        final BlogPost exampleRequest = BlogPost.newBuilder()
                                                .setTitle("My first blog")
                                                .setContent("Hello Armeria!")
                                                .build();
        final DocService docService = DocService.builder()
                                                .exampleRequests(BlogServiceGrpc.SERVICE_NAME,
                                                                 "CreateBlogPost", exampleRequest)
                                                .exclude(DocServiceFilter.ofServiceName(
                                                        ServerReflectionGrpc.SERVICE_NAME))
                                                .build();
        return Server.builder()
                     .http(port)
                     .service(grpcService)
                     // You can access the documentation service at http://127.0.0.1:8080/docs.
                     // See https://armeria.dev/docs/server-docservice for more information.
                     .serviceUnder("/docs", docService)
                     .build();
    }

    private Main() {}
}
