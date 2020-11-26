<%@ page import="org.slf4j.Logger" %>
<%@ page contentType="text/html; charset=UTF-8" %>
<html>
<body>
<%-- Attempt to access the class that exists only in WEB-INF/lib/hello.jar --%>
<p><%= com.linecorp.example.HelloWorld.get("Armerian World") %></p>
<%-- Attempt to access the class that exists in the system class path --%>
<p>Have you heard about the class '<%= Logger.class.getName() %>'?</p>
<%-- Print some request properties for testing purpose. --%>
<p>Context path: <%= request.getContextPath() %></p>
<p>Request URI: <%= request.getRequestURI() %></p>
<p>Scheme: <%= request.getScheme() %></p>
<p>Protocol: <%= request.getProtocol() %></p>
</body>
</html>
