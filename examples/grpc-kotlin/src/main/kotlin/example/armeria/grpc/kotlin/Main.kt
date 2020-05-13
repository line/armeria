package example.armeria.grpc.kotlin

import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.server.docs.DocServiceFilter
import com.linecorp.armeria.server.grpc.GrpcService
import example.armeria.grpc.kotlin.Hello.HelloRequest
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.reflection.v1alpha.ServerReflectionGrpc
import org.slf4j.LoggerFactory

object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        val server = newServer(8080, 8443)

        Runtime.getRuntime().addShutdownHook(Thread(Runnable {
            server.stop().join()
            logger.info("Server has been stopped.")
        }))

        server.start().join()
        server.activePort()?.let {
            val localAddress = it.localAddress()
            val isLocalAddress = localAddress.address.isAnyLocalAddress ||
                                    localAddress.address.isLoopbackAddress
            logger.info("Server has been started. Serving DocService at http://{}:{}/docs",
                        if (isLocalAddress) "127.0.0.1" else localAddress.hostString, localAddress.port)
        }
    }

    private val logger = LoggerFactory.getLogger(Main::class.java)

    fun newServer(httpPort: Int, httpsPort: Int): Server {
        val exampleRequest: HelloRequest = HelloRequest.newBuilder().setName("Armeria").build()
        val grpcService = GrpcService.builder()
                .addService(HelloServiceImpl())
                // See https://github.com/grpc/grpc-java/blob/master/documentation/server-reflection-tutorial.md
                .addService(ProtoReflectionService.newInstance())
                .supportedSerializationFormats(GrpcSerializationFormats.values())
                .enableUnframedRequests(true)
                // You can set useBlockingTaskExecutor(true) in order to execute all gRPC
                // methods in the blockingTaskExecutor thread pool.
                // .useBlockingTaskExecutor(true)
                .build()
        return Server.builder()
                .http(httpPort)
                .https(httpsPort)
                .tlsSelfSigned()
                .service(grpcService) // You can access the documentation service at http://127.0.0.1:8080/docs.
                // See https://line.github.io/armeria/docs/server-docservice for more information.
                .serviceUnder("/docs",
                        DocService.builder()
                                .exampleRequestForMethod(HelloServiceGrpc.SERVICE_NAME,
                                        "Hello", exampleRequest)
                                .exampleRequestForMethod(HelloServiceGrpc.SERVICE_NAME,
                                        "LazyHello", exampleRequest)
                                .exampleRequestForMethod(HelloServiceGrpc.SERVICE_NAME,
                                        "BlockingHello", exampleRequest)
                                .exclude(DocServiceFilter.ofServiceName(
                                        ServerReflectionGrpc.SERVICE_NAME))
                                .build())
                .build()
    }
}
