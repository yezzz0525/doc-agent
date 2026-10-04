<!-- 来源: https://docs.spring.io/spring-boot/reference/messaging/rsocket.html -->

# RSocket

RSocket is a binary protocol for use on byte stream transports.
It enables symmetric interaction models through async message passing over a single connection.

The spring-messaging module of the Spring Framework provides support for RSocket requesters and responders, both on the client and on the server side.
See the RSocket section of the Spring Framework reference for more details, including an overview of the RSocket protocol.

## RSocket Strategies Auto-configuration

Spring Boot auto-configures an RSocketStrategies bean that provides all the required infrastructure for encoding and decoding RSocket payloads.
By default, the auto-configuration will try to configure the following (in order):

-
CBOR codecs with Jackson

-
JSON codecs with Jackson

The spring-boot-starter-rsocket starter provides both dependencies.
See the Jackson support section to know more about customization possibilities.

Developers can customize the RSocketStrategies component by creating beans that implement the RSocketStrategiesCustomizer interface.
Note that their @Order is important, as it determines the order of codecs.

## RSocket Server Auto-configuration

Spring Boot provides RSocket server auto-configuration.
The required dependencies are provided by the spring-boot-starter-rsocket.

Spring Boot allows exposing RSocket over WebSocket from a WebFlux server, or standing up an independent RSocket server.
This depends on the type of application and its configuration.

For WebFlux application (that is of type WebApplicationType.REACTIVE), the RSocket server will be plugged into the Web Server only if the following properties match:

-
Properties

-
YAML

```
spring.rsocket.server.mapping-path=/rsocket
spring.rsocket.server.transport=websocket
```

```
spring:
  rsocket:
    server:
      mapping-path: "/rsocket"
      transport: "websocket"
```

Plugging RSocket into a web server is only supported with Reactor Netty, as RSocket itself is built with that library.

Alternatively, an RSocket TCP or websocket server is started as an independent, embedded server.
Besides the dependency requirements, the only required configuration is to define a port for that server:

-
Properties

-
YAML

```
spring.rsocket.server.port=9898
```

```
spring:
  rsocket:
    server:
      port: 9898
```

## Spring Messaging RSocket Support

Spring Boot will auto-configure the Spring Messaging infrastructure for RSocket.

This means that Spring Boot will create a RSocketMessageHandler bean that will handle RSocket requests to your application.

You can use @ControllerAdvice to handle exceptions.

## Calling RSocket Services with RSocketRequester

Once the RSocket channel is established between server and client, any party can send or receive requests to the other.

As a server, you can get injected with an RSocketRequester instance on any handler method of an RSocket @Controller.
As a client, you need to configure and establish an RSocket connection first.
Spring Boot auto-configures an RSocketRequester.Builder for such cases with the expected codecs and applies any RSocketConnectorConfigurer bean.

The RSocketRequester.Builder instance is a prototype bean, meaning each injection point will provide you with a new instance .
This is done on purpose since this builder is stateful and you should not create requesters with different setups using the same instance.

The following code shows a typical example:

-
Java

-
Kotlin

```
import reactor.core.publisher.Mono;

import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Service;

@Service
public class MyService {

	private final RSocketRequester rsocketRequester;

	public MyService(RSocketRequester.Builder rsocketRequesterBuilder) {
		this.rsocketRequester = rsocketRequesterBuilder.tcp("example.org", 9898);
	}

	public Mono<User> someRSocketCall(String name) {
		return this.rsocketRequester.route("user").data(name).retrieveMono(User.class);
	}

}
```

```
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class MyService(rsocketRequesterBuilder: RSocketRequester.Builder) {

	private val rsocketRequester: RSocketRequester

	init {
		rsocketRequester = rsocketRequesterBuilder.tcp("example.org", 9898)
	}

	fun someRSocketCall(name: String): Mono<User> {
		return rsocketRequester.route("user").data(name).retrieveMono(
			User::class.java
		)
	}

}
```