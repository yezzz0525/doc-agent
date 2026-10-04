<!-- 来源: https://docs.spring.io/spring-boot/reference/messaging/websockets.html -->

# WebSockets

Spring Boot provides WebSockets auto-configuration for embedded Tomcat and Jetty.
If you deploy a war file to a standalone container, Spring Boot assumes that the container is responsible for the configuration of its WebSocket support.

Spring Framework provides rich WebSocket support for MVC web applications that can be easily accessed through the spring-boot-starter-websocket module.

WebSocket support is also available for reactive web applications and requires to include the WebSocket API alongside spring-boot-starter-webflux:

```
<dependency>
	<groupId>jakarta.websocket</groupId>
	<artifactId>jakarta.websocket-api</artifactId>
</dependency>
```