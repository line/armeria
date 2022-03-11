package example.armeria.grpc.scala

import com.google.common.base.Stopwatch
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.SerializationFormat
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller
import com.linecorp.armeria.server.Server
import example.armeria.grpc.scala.HelloServiceImpl.toMessage
import example.armeria.grpc.scala.HelloServiceTest.{GrpcSerializationProvider, newClient}
import example.armeria.grpc.scala.hello.HelloServiceGrpc.{HelloServiceBlockingStub, HelloServiceStub}
import example.armeria.grpc.scala.hello.{HelloReply, HelloRequest}
import io.grpc.stub.StreamObserver
import java.time
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.stream
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.{Arguments, ArgumentsProvider, ArgumentsSource}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

class HelloServiceTest {

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def getReply(serializationFormat: SerializationFormat): Unit = {
    val helloService = newClient[HelloServiceBlockingStub](serializationFormat)
    assertThat(helloService.hello(HelloRequest("Armeria")).message).isEqualTo("Hello, Armeria!")
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def replyWithDelay(serializationFormat: SerializationFormat): Unit = {
    val helloService = newClient[HelloServiceStub](serializationFormat)
    val reply: HelloReply = Await.result(helloService.lazyHello(HelloRequest("Armeria")), Duration.Inf)
    assertThat(reply.message).isEqualTo("Hello, Armeria!")
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def replyFromServerSideBlockingCall(serializationFormat: SerializationFormat): Unit = {
    val helloService = newClient[HelloServiceStub](serializationFormat)
    val watch = Stopwatch.createStarted()
    val reply: HelloReply = Await.result(helloService.blockingHello(HelloRequest("Armeria")), Duration.Inf)
    assertThat(reply.message).isEqualTo("Hello, Armeria!")
    assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3)
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def lotsOfReplies(serializationFormat: SerializationFormat): Unit = {
    val completed = new AtomicBoolean()
    val helloService = newClient[HelloServiceStub](serializationFormat)
    val sequence = new AtomicInteger()

    helloService.lotsOfReplies(
      HelloRequest("Armeria"),
      new StreamObserver[HelloReply]() {

        override def onNext(value: HelloReply): Unit = {
          sequence.incrementAndGet()
          assertThat(value.message).isEqualTo(s"Hello, Armeria! (sequence: $sequence)")
        }

        override def onError(t: Throwable): Unit =
          // Should never reach here.
          throw new Error(t)

        override def onCompleted(): Unit = {
          assertThat(sequence.get()).isEqualTo(5)
          completed.set(true)
        }
      }
    )

    await().atMost(time.Duration.ofSeconds(15)).untilAsserted(() => { assertThat(completed.get()).isTrue() })
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def sendLotsOfGreetings(serializationFormat: SerializationFormat): Unit = {
    val names = List("Armeria", "Grpc", "Streaming")
    val completed = new AtomicBoolean()
    val helloService = newClient[HelloServiceStub](serializationFormat)

    val request = helloService.lotsOfGreetings(new StreamObserver[HelloReply]() {
      private var received = false

      override def onNext(value: HelloReply): Unit = {
        assertThat(received).isFalse()
        received = true
        assertThat(value.message).isEqualTo(toMessage(names.mkString(", ")))
      }

      override def onError(t: Throwable): Unit =
        // Should never reach here.
        throw new Error(t)

      override def onCompleted(): Unit = {
        assertThat(received).isTrue()
        completed.set(true)
      }
    })

    for (name <- names)
      request.onNext(HelloRequest(name))
    request.onCompleted()

    await().untilAsserted(() => assertThat(completed.get()).isTrue())
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def bidirectionalHello(serializationFormat: SerializationFormat): Unit = {
    val names = List("Armeria", "Grpc", "Streaming")
    val completed = new AtomicBoolean()
    val helloService = newClient[HelloServiceStub](serializationFormat)

    val request = helloService.bidiHello(new StreamObserver[HelloReply]() {
      private var received = 0

      override def onNext(value: HelloReply): Unit = {
        assertThat(value.message).isEqualTo(toMessage(names(received)))
        received += 1
      }

      override def onError(t: Throwable): Unit =
        // Should never reach here.
        throw new Error(t)

      override def onCompleted(): Unit = {
        assertThat(received).isEqualTo(names.length)
        completed.set(true)
      }
    })

    for (name <- names)
      request.onNext(HelloRequest(name))
    request.onCompleted()

    await().untilAsserted(() => assertThat(completed.get()).isTrue())
  }
}

object HelloServiceTest {

  var server: Server = _

  private def newClient[A](serializationFormat: SerializationFormat = GrpcSerializationFormats.PROTO)(implicit
      tag: ClassTag[A]): A = {
    GrpcClients
      .builder(uri(serializationFormat))
      .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
      .build(tag.runtimeClass)
      .asInstanceOf[A]
  }

  private def uri(serializationFormat: SerializationFormat = GrpcSerializationFormats.PROTO): String =
    s"$serializationFormat+http://127.0.0.1:${server.activeLocalPort()}/"

  @BeforeAll
  def beforeClass(): Unit = {
    server = Main.newServer(0, 0)
    server.start().join()
  }

  private class GrpcSerializationProvider extends ArgumentsProvider {
    override def provideArguments(context: ExtensionContext): stream.Stream[_ <: Arguments] =
      GrpcSerializationFormats
        .values()
        .stream()
        .map(Arguments.of(_))
  }
}
