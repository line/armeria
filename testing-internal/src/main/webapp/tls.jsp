<%@ page import="java.security.cert.X509Certificate"%>
<%@ page contentType="application/json; charset=UTF-8" %>
{
    "sessionId": "<%= request.getAttribute("javax.servlet.request.ssl_session_id") %>",
    "cipherSuite": "<%= request.getAttribute("javax.servlet.request.cipher_suite") %>",
    "keySize": <%= request.getAttribute("javax.servlet.request.key_size") %>,
    "hasPeerCerts": <%= request.getAttribute("javax.servlet.request.X509Certificate") != null %>
}
