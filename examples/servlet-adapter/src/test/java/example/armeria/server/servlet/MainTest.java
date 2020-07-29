package example.armeria.server.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MainTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            Main.getServerBuilder(sb);
        }
    };

    @Test
    void doGet() {
        final WebClient client = WebClient.of(server.httpUri());
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/app/home?test=1");
        final AggregatedHttpResponse res = client.execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isSameAs(MediaType.HTML_UTF_8);
        final String setCookie = res.headers().get(HttpHeaderNames.SET_COOKIE);
        final Cookie cookie = Cookie.fromSetCookieHeader(setCookie);
        assertThat(cookie.name()).isEqualTo("armeria");
        assertThat(cookie.value()).isEqualTo("session_id_1");
    }

    @Test
    void doPost() {
        final WebClient client = WebClient.of(server.httpUri());
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/app/home?type=servlet",
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA);
        final HttpData body = HttpData.ofUtf8("application=Armeria Servlet");
        final AggregatedHttpResponse res = client.execute(headers, body).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isSameAs(MediaType.HTML_UTF_8);
        assertThat(res.contentUtf8()).contains("APPLICATION: Armeria Servlet");
    }
}
