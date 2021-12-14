package example.armeria.grpc.kotlin

import com.google.common.base.Stopwatch
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.server.Server
import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest
import example.armeria.grpc.kotlin.HelloServiceGrpcKt.HelloServiceCoroutineStub
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit

class HelloServiceTest {

    @ParameterizedTest
    @MethodSource("uris")
    fun reply(uri: String) {
        runBlocking {
            val helloService = Clients.newClient(uri, HelloServiceCoroutineStub::class.java)
            assertThat(helloService.hello(HelloRequest.newBuilder().setName("Armeria").build()).message)
                .isEqualTo("Hello, Armeria!")
        }
    }

    @ParameterizedTest
    @MethodSource("uris")
    fun replyWithDelay(uri: String) {
        runBlocking {
            val helloService = Clients.newClient(uri, HelloServiceCoroutineStub::class.java)
            val reply: HelloReply = helloService.lazyHello(HelloRequest.newBuilder().setName("Armeria").build())
            assertThat(reply.message).isEqualTo("Hello, Armeria!")
        }
    }

    @ParameterizedTest
    @MethodSource("uris")
    fun replyFromServerSideBlockingCall(uri: String) {
        runBlocking {
            val helloService = Clients.newClient(uri, HelloServiceCoroutineStub::class.java)
            val watch = Stopwatch.createStarted()
            assertThat(helloService.blockingHello(HelloRequest.newBuilder().setName("Armeria").build()).message)
                .isEqualTo("Hello, Armeria!")
            assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3)
        }
    }

    @Test
    fun lotsOfReplies() {
        runBlocking {
            var sequence = 0
            helloService.lotsOfReplies(HelloRequest.newBuilder().setName("Armeria").build())
                .collect {
                    assertThat(it.message).isEqualTo("Hello, Armeria! (sequence: ${++sequence})")
                }
            assertThat(sequence).isEqualTo(5)
        }
    }

    @Test
    fun blockForLotsOfReplies() {
        runBlocking {
            val replies = ArrayList<HelloReply>()
            helloService.lotsOfReplies(HelloRequest.newBuilder().setName("Armeria").build())
                .collect { replies.add(it) }
            for ((sequence, reply) in replies.withIndex()) {
                assertThat(reply.message).isEqualTo("Hello, Armeria! (sequence: ${sequence + 1})")
            }
        }
    }

    @Test
    fun sendLotsOfGreetings() {
        runBlocking {
            val names = listOf("Armeria", "Grpc", "Streaming")
            val requests = names.map { HelloRequest.newBuilder().setName(it).build() }

            val reply: HelloReply = helloService.lotsOfGreetings(requests.asFlow())
            assertThat(reply.message).isEqualTo("Hello, ${names.joinToString()}!")
        }
    }

    @Test
    fun bidirectionalHello() {
        runBlocking {
            val names = listOf("Armeria", "Grpc", "Streaming")
            val requests = names.map { HelloRequest.newBuilder().setName(it).build() }
            val replies = helloService.bidiHello(requests.asFlow())

            var received = 0
            replies.collect {
                assertThat(it.message).isEqualTo("Hello, ${names[received++]}!")
            }
        }
    }

    companion object {

        private lateinit var server: Server
        private lateinit var blockingServer: Server
        private lateinit var helloService: HelloServiceCoroutineStub

        @BeforeAll
        @JvmStatic
        fun beforeClass() {
            server = Main.newServer(0, 0)
            server.start().join()

            blockingServer = Main.newServer(0, 0, true)
            blockingServer.start().join()
            helloService = Clients.newClient(protoUri(), HelloServiceCoroutineStub::class.java)
        }

        @AfterAll
        @JvmStatic
        fun afterClass() {
            server.stop().join()
            blockingServer.stop().join()
        }

        @JvmStatic
        fun uris() = listOf(protoUri(), jsonUri(), blockingProtoUri(), blockingJsonUri())
            .map { Arguments.of(it) }

        private fun protoUri(): String {
            return "gproto+http://127.0.0.1:" + server.activeLocalPort() + '/'
        }

        private fun jsonUri(): String {
            return "gjson+http://127.0.0.1:" + server.activeLocalPort() + '/'
        }

        private fun blockingProtoUri(): String {
            return "gproto+http://127.0.0.1:" + blockingServer.activeLocalPort() + '/'
        }

        private fun blockingJsonUri(): String {
            return "gjson+http://127.0.0.1:" + blockingServer.activeLocalPort() + '/'
        }
    }
}
