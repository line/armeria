<%@ page import="java.security.cert.X509Certificate"%>
<%@ page contentType="application/json; charset=UTF-8" %>
{
    "remoteHost": "<%= request.getRemoteHost() %>",
    "sessionId": "<%= request.getAttribute("javax.servlet.request.ssl_session_id") %>",
    "cipherSuite": "<%= request.getAttribute("javax.servlet.request.cipher_suite") %>",
    "keySize": <%= request.getAttribute("javax.servlet.request.key_size") %>,
    "peerCerts": [ <%
        final X509Certificate[] certs =
              (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
        if (certs != null) {
            boolean first = true;
            for (X509Certificate c : certs) {
                if (!first) {
                    out.write(',');
                } else {
                    first = false;
                }
                out.write('"');
                out.write(c.getSubjectX500Principal().getName());
                out.write('"');
            }
       }
    %> ]
}
