package com.linecorp.armeria.server.scalapb

import com.google.common.base.Stopwatch
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.SerializationFormat
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.scalapb.TestServiceImpl.toMessage
import com.linecorp.armeria.server.scalapb.TestServiceTest.{GrpcSerializationProvider, newClient}
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.{Arguments, ArgumentsProvider, ArgumentsSource}
import testing.scalapb.service.hello.TestServiceGrpc.{TestServiceBlockingStub, TestServiceStub}
import testing.scalapb.service.hello._

import java.time
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.stream
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.reflect.ClassTag

class TestServiceTest {

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def getReply(serializationFormat: SerializationFormat): Unit = {
    val TestService = newClient[TestServiceBlockingStub](serializationFormat)
    assertThat(TestService.hello(HelloRequest("Armeria")).message).isEqualTo("Hello, Armeria!")
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def replyWithDelay(serializationFormat: SerializationFormat): Unit = {
    val TestService = newClient[TestServiceStub](serializationFormat)
    val reply: HelloReply = Await.result(TestService.lazyHello(HelloRequest("Armeria")), Duration.Inf)
    assertThat(reply.message).isEqualTo("Hello, Armeria!")
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def replyFromServerSideBlockingCall(serializationFormat: SerializationFormat): Unit = {
    val TestService = newClient[TestServiceStub](serializationFormat)
    val watch = Stopwatch.createStarted()
    val reply: HelloReply = Await.result(TestService.blockingHello(HelloRequest("Armeria")), Duration.Inf)
    assertThat(reply.message).isEqualTo("Hello, Armeria!")
    assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3)
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def lotsOfReplies(serializationFormat: SerializationFormat): Unit = {
    val completed = new AtomicBoolean()
    val TestService = newClient[TestServiceStub](serializationFormat)
    val sequence = new AtomicInteger()

    TestService.lotsOfReplies(
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
          assertThat(sequence).overridingErrorMessage(() => s"sequence is $sequence").hasValue(5)
          completed.set(true)
        }
      }
    )
    await().atMost(time.Duration.ofSeconds(15)).untilAsserted(() => assertThat(completed).isTrue())
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def sendLotsOfGreetings(serializationFormat: SerializationFormat): Unit = {
    val names = List("Armeria", "Grpc", "Streaming")
    val completed = new AtomicBoolean()
    val TestService = newClient[TestServiceStub](serializationFormat)

    val request = TestService.lotsOfGreetings(new StreamObserver[HelloReply]() {
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

    await().untilAsserted(() => assertThat(completed).isTrue())
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def bidirectionalHello(serializationFormat: SerializationFormat): Unit = {
    val names = List("Armeria", "Grpc", "Streaming")
    val completed = new AtomicBoolean()
    val TestService = newClient[TestServiceStub](serializationFormat)

    val request = TestService.bidiHello(new StreamObserver[HelloReply]() {
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

    await().untilAsserted(() => assertThat(completed).isTrue())
  }

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def oneof(serializationFormat: SerializationFormat): Unit = {
    val oneof: Add = Add(Literal(1), Literal(2))
    val TestService = newClient[TestServiceStub](serializationFormat)
    val actual = TestService.oneof(oneof)
    val res = Await.result(actual, Duration.Inf)
    assertThat(res).isEqualTo(oneof)
  }
}

object TestServiceTest {

  var server: ServerExtension = new ServerExtension() {
    override protected def configure(sb: ServerBuilder): Unit =
      sb.service(
        GrpcService
          .builder()
          .addService(TestServiceGrpc.bindService(new TestServiceImpl, ExecutionContext.global))
          .supportedSerializationFormats(GrpcSerializationFormats.values)
          .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
          .enableUnframedRequests(true)
          .build()
      )
  }

  private def newClient[A](serializationFormat: SerializationFormat = GrpcSerializationFormats.PROTO)(implicit
      tag: ClassTag[A]): A = {
    GrpcClients
      .builder(server.httpUri(serializationFormat))
      .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
      .build(tag.runtimeClass)
      .asInstanceOf[A]
  }

  @BeforeAll
  def beforeClass(): Unit =
    server.start()

  private class GrpcSerializationProvider extends ArgumentsProvider {
    override def provideArguments(context: ExtensionContext): stream.Stream[_ <: Arguments] =
      GrpcSerializationFormats
        .values()
        .stream()
        .map(Arguments.of(_))
  }
}
