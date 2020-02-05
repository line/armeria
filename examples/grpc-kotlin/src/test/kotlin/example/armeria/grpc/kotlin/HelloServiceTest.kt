package example.armeria.grpc.kotlin

import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.server.Server
import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest
import example.armeria.grpc.kotlin.HelloServiceGrpc.*
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HelloServiceTest {

    @Test
    fun reply() {
        val helloService = Clients.newClient(uri(), HelloServiceBlockingStub::class.java)
        assertThat(helloService.hello(HelloRequest.newBuilder().setName("Armeria").build()).message)
                .isEqualTo("Hello, Armeria!")
    }

    // Should never reach here.
    @Test
    fun replyWithDelay() {
        val helloService = Clients.newClient(uri(), HelloServiceFutureStub::class.java)
        val future = helloService.lazyHello(HelloRequest.newBuilder().setName("Armeria").build())
        val completed = AtomicBoolean()
        Futures.addCallback(future, object : FutureCallback<HelloReply> {
            override fun onSuccess(result: HelloReply?) {
                assertThat(result?.message).isEqualTo("Hello, Armeria!")
                completed.set(true)
            }

            override fun onFailure(t: Throwable) { // Should never reach here.
                throw Error(t)
            }
        }, MoreExecutors.directExecutor())

        await().untilTrue(completed)
    }

    @Test
    fun replyFromServerSideBlockingCall() {
        val helloService = Clients.newClient(uri(), HelloServiceBlockingStub::class.java)
        val watch = Stopwatch.createStarted()
        assertThat(helloService.blockingHello(HelloRequest.newBuilder().setName("Armeria").build()).message)
                .isEqualTo("Hello, Armeria!")
        assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3)
    }

    // Should never reach here.
    @Test
    fun lotsOfReplies() {
        val completed = AtomicBoolean()
        helloService.lotsOfReplies(
                HelloRequest.newBuilder().setName("Armeria").build(),
                object : StreamObserver<HelloReply> {
                    private var sequence = 0
                    override fun onNext(value: HelloReply) {
                        assertThat(value.message).isEqualTo("Hello, Armeria! (sequence: ${++sequence})")
                    }

                    override fun onError(t: Throwable) { // Should never reach here.
                        throw Error(t)
                    }

                    override fun onCompleted() {
                        assertThat(sequence).isEqualTo(5)
                        completed.set(true)
                    }
                })
        await().untilTrue(completed)
    }

    @Test
    fun blockForLotsOfReplies() {
        val replies = LinkedBlockingQueue<HelloReply>()
        val completed = AtomicBoolean()
        helloService.lotsOfReplies(
                HelloRequest.newBuilder().setName("Armeria").build(),
                object : StreamObserver<HelloReply> {
                    override fun onNext(value: HelloReply) {
                        replies.offer(value)
                    }

                    override fun onError(t: Throwable) { // Should never reach here.
                        throw Error(t)
                    }

                    override fun onCompleted() {
                        completed.set(true)
                    }
                })
        var sequence = 0
        while (completed.get().not() or replies.isNotEmpty()) {
            val value = replies.poll(100, TimeUnit.MILLISECONDS) ?: continue
            assertThat(value.message).isEqualTo("Hello, Armeria! (sequence: ${++sequence})")
        }
        assertThat(sequence).isEqualTo(5)
    }

    @Test
    fun sendLotsOfGreetings() {
        val names = listOf("Armeria", "Grpc", "Streaming")
        val completed = AtomicBoolean()
        val request = helloService.lotsOfGreetings(object : StreamObserver<HelloReply> {
            private var received = false
            override fun onNext(value: HelloReply) {
                assertThat(received).isFalse()
                received = true
                assertThat(value.message).isEqualTo("Hello, ${names.joinToString()}!")
            }

            override fun onError(t: Throwable) { // Should never reach here.
                throw Error(t)
            }

            override fun onCompleted() {
                assertThat(received).isTrue()
                completed.set(true)
            }
        })
        for (name in names) {
            request.onNext(HelloRequest.newBuilder().setName(name).build())
        }
        request.onCompleted()
        await().untilTrue(completed)
    }

    @Test
    fun bidirectionalHello() {
        val names = listOf("Armeria", "Grpc", "Streaming")
        val completed = AtomicBoolean()
        val request = helloService.bidiHello(object : StreamObserver<HelloReply> {
            private var received = 0
            override fun onNext(value: HelloReply) {
                assertThat(value.message).isEqualTo("Hello, ${names[received++]}!")
            }

            override fun onError(t: Throwable) { // Should never reach here.
                throw Error(t)
            }

            override fun onCompleted() {
                assertThat(received).isEqualTo(names.size)
                completed.set(true)
            }
        })
        for (name in names) {
            request.onNext(HelloRequest.newBuilder().setName(name).build())
        }
        request.onCompleted()
        await().untilTrue(completed)
    }

    companion object {

        private lateinit var server: Server
        private lateinit var helloService: HelloServiceStub

        @BeforeAll
        @JvmStatic
        fun beforeClass() {
            server = Main.newServer(0, 0)
            server.start().join()
            helloService = Clients.newClient(uri(), HelloServiceStub::class.java)
        }

        @AfterAll
        @JvmStatic
        fun afterClass() {
            server.stop().join()
        }

        private fun uri(): String {
            return "gproto+http://127.0.0.1:" + server.activeLocalPort() + '/'
        }
    }
}
