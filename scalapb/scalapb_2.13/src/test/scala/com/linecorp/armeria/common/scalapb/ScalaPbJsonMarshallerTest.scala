package com.linecorp.armeria.common.scalapb

import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import scalapb.GeneratedSealedOneof
import testing.scalapb.messages.{Add, Literal}

import java.io.ByteArrayOutputStream

@GenerateNativeImageTrace
class ScalaPbJsonMarshallerTest {

  @Test
  def serializeOneOf(): Unit = {
    val marshaller = ScalaPbJsonMarshaller()
    val os = new ByteArrayOutputStream()

    val literal = Literal(10)
    assertThat(literal).isInstanceOf(classOf[GeneratedSealedOneof])
    marshaller.serializeMessage(null, literal, os)
    assertThatJson(new String(os.toByteArray))
      .isEqualTo("""
          |{
          |  "lit": { "value": 10 }
          |}""".stripMargin)

    os.reset()
    val add = Add(Literal(1), Literal(2))
    assertThat(add).isInstanceOf(classOf[GeneratedSealedOneof])
    marshaller.serializeMessage(null, add, os)
    assertThatJson(new String(os.toByteArray))
      .isEqualTo("""
          |{
          |  "add": {
          |    "left": {
          |      "lit": { "value": 1 }
          |    },
          |    "right":{
          |      "lit":{ "value": 2 }
          |    }
          |  }
          |}""".stripMargin)
  }
}
