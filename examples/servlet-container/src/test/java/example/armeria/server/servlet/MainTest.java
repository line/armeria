package example.armeria.server.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.netty.handler.codec.http.HttpConstants;

public class MainTest {
    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb = Main.getServerBuilder(sb);
        }
    };

    static {
        rule.start();
    }

    @Test
    void doGet() throws Exception {
        final HttpGet req = new HttpGet(rule.httpUri() + "/app/home?test=1");
        req.setHeader(HttpHeaderNames.ACCEPT_LANGUAGE.toString(), "en-US");
        req.setHeader(HttpHeaderNames.COOKIE.toString(), "armeria=session_id_1");
        req.setHeader("start_date", "Tue May 12 12:55:48 2020");
        req.setHeader("start_flag", "1");
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith(MediaType.HTML_UTF_8.toString());
                assertThat(res.getHeaders(HttpHeaderNames.SET_COOKIE.toString())[0]
                                   .getElements()[0].getValue()).isEqualTo("session_id_1");
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void doPost() throws Exception {
        final HttpPost req = new HttpPost(rule.httpUri() + "/app/home?type=servlet");
        final List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("application", "Armeria Servlet"));
        req.setHeader(HttpHeaderNames.COOKIE.toString(), "armeria=session_id_1");
        final UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params, HttpConstants.DEFAULT_CHARSET);
        req.setEntity(entity);
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith(MediaType.HTML_UTF_8.toString());
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void doPut() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpPut(rule.httpUri() + "/app/home"))) {
                assertThat(res.getStatusLine().getStatusCode()).isEqualTo(302);
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void doDelete() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpDelete(rule.httpUri() + "/app/home"))) {
                assertThat(res.getStatusLine().getStatusCode()).isEqualTo(404);
                EntityUtils.consume(res.getEntity());
            }
        }
    }
}
