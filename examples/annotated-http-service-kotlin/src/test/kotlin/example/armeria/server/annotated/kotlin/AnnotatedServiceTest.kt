package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class AnnotatedServiceTest {

    companion object {

        @JvmField
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                configureServices(sb)
            }
        }

        fun client(): WebClient {
            return WebClient.of(server.httpUri())
        }
    }

    @Test
    fun testContextAwareService() {
        val res = client().get("/contextAware/foo?name=armeria&id=100").aggregate().join()
        assertThat(res.status()).isEqualTo(HttpStatus.OK)
        assertThatJson(res.contentUtf8())
            .node("id").isEqualTo(100)
            .node("name").isEqualTo("armeria")
    }

    @Test
    fun testDecoratingService() {
        val client = client()
        client.get("/decorating/foo").aggregate().join().let {
            assertThat(it.status()).isEqualTo(HttpStatus.OK)
            assertThat(it.contentUtf8()).isEqualTo("OK")
        }

        client.get("/decorating/bar").aggregate().join().let {
            assertThat(it.status()).isEqualTo(HttpStatus.OK)
            assertThat(it.contentUtf8()).isEqualTo("OK")
        }

        client.get("/decorating/blocking").aggregate().join().let {
            assertThat(it.status()).isEqualTo(HttpStatus.OK)
            assertThat(it.contentUtf8()).isEqualTo("OK")
        }
    }

    @Test
    fun testNothingReturnType() {
        val client = client()
        client.get("/throw/error").aggregate().join().let {
            assertThat(it.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }
}
