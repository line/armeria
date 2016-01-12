<%@ page contentType="text/html; charset=UTF-8" %>
<html>
<body>
<p>RemoteAddr: <%= request.getRemoteAddr() %></p>
<p>RemoteHost: <%= request.getRemoteHost() %></p>
<p>RemotePort: <%= request.getRemotePort() %></p>
<p>LocalAddr: <%= request.getLocalAddr() %></p>
<p>LocalName: <%= request.getLocalName() %></p>
<p>LocalPort: <%= request.getLocalPort() %></p>
</body>
</html>
