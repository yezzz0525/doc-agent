<!-- 来源: https://docs.spring.io/spring-boot/reference/messaging/pulsar.html -->

# Apache Pulsar Support

Apache Pulsar is supported by providing auto-configuration of the Spring for Apache Pulsar project.

Spring Boot will auto-configure and register the Spring for Apache Pulsar components when org.springframework.pulsar:spring-pulsar is on the classpath.

There is the spring-boot-starter-pulsar starter for conveniently collecting the dependencies for use.

## Connecting to Pulsar

When you use the Pulsar starter, Spring Boot will auto-configure and register a PulsarClient bean.

By default, the application tries to connect to a local Pulsar instance at pulsar://localhost:6650.
This can be adjusted by setting the spring.pulsar.client.service-url property to a different value.

The value must be a valid Pulsar Protocol URL

You can configure the client by specifying any of the spring.pulsar.client.* prefixed application properties.

If you need more control over the configuration, consider registering one or more PulsarClientBuilderCustomizer beans.

### Authentication

To connect to a Pulsar cluster that requires authentication, you need to specify which authentication plugin to use by setting the pluginClassName and any parameters required by the plugin.
You can set the parameters as a map of parameter names to parameter values.
The following example shows how to configure the AuthenticationOAuth2 plugin.

-
Properties

-
YAML

```
spring.pulsar.client.authentication.plugin-class-name=org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2
spring.pulsar.client.authentication.param.issuerUrl=https://auth.server.cloud/
spring.pulsar.client.authentication.param.privateKey=file:///Users/some-key.json
spring.pulsar.client.authentication.param.audience=urn:sn:acme:dev:my-instance
```

```
spring:
  pulsar:
    client:
      authentication:
        plugin-class-name: org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2
        param:
          issuerUrl: https://auth.server.cloud/
          privateKey: file:///Users/some-key.json
          audience: urn:sn:acme:dev:my-instance
```

You need to ensure that names defined under spring.pulsar.client.authentication.param.* exactly match those expected by your auth plugin (which is typically camel cased).
Spring Boot will not attempt any kind of relaxed binding for these entries.

For example, if you want to configure the issuer url for the AuthenticationOAuth2 auth plugin you must use spring.pulsar.client.authentication.param.issuerUrl.
If you use other forms, such as issuerurl or issuer-url, the setting will not be applied to the plugin.

This lack of relaxed binding also makes using environment variables for authentication parameters problematic because the case sensitivity is lost during translation.
If you use environment variables for the parameters then you will need to follow these steps in the Spring for Apache Pulsar reference documentation for it to work properly.

### SSL

By default, Pulsar clients communicate with Pulsar services in plain text.
You can follow these steps in the Spring for Apache Pulsar reference documentation to enable TLS encryption.

For complete details on the client and authentication see the Spring for Apache Pulsar reference documentation.

## Connecting to Pulsar Administration

Spring for Apache Pulsar’s PulsarAdministration client is also auto-configured.

By default, the application tries to connect to a local Pulsar instance at http://localhost:8080.
This can be adjusted by setting the spring.pulsar.admin.service-url property to a different value in the form (http|https)://<host>:<port>.

If you need more control over the configuration, consider registering one or more PulsarAdminBuilderCustomizer beans.

### Authentication

When accessing a Pulsar cluster that requires authentication, the admin client requires the same security configuration as the regular Pulsar client.
You can use the aforementioned authentication configuration by replacing spring.pulsar.client.authentication with spring.pulsar.admin.authentication.

To create a topic on startup, add a bean of type PulsarTopic.
If the topic already exists, the bean is ignored.

## Sending a Message

Spring’s PulsarTemplate is auto-configured, and you can use it to send messages, as shown in the following example:

-
Java

-
Kotlin

```
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

@Component
public class MyBean {

	private final PulsarTemplate<String> pulsarTemplate;

	public MyBean(PulsarTemplate<String> pulsarTemplate) {
		this.pulsarTemplate = pulsarTemplate;
	}

	public void someMethod() {
		this.pulsarTemplate.send("someTopic", "Hello");
	}

}
```

```
import org.apache.pulsar.client.api.PulsarClientException
import org.springframework.pulsar.core.PulsarTemplate
import org.springframework.stereotype.Component

@Component
class MyBean(private val pulsarTemplate: PulsarTemplate<String>) {

	@Throws(PulsarClientException::class)
	fun someMethod() {
		pulsarTemplate.send("someTopic", "Hello")
	}

}
```

The PulsarTemplate relies on a PulsarProducerFactory to create the underlying Pulsar producer.
Spring Boot auto-configuration also provides this producer factory, which by default, caches the producers that it creates.
You can configure the producer factory and cache settings by specifying any of the spring.pulsar.producer.* and spring.pulsar.producer.cache.* prefixed application properties.

If you need more control over the producer factory configuration, consider registering one or more ProducerBuilderCustomizer beans.
These customizers are applied to all created producers.
You can also pass in a ProducerBuilderCustomizer when sending a message to only affect the current producer.

If you need more control over the message being sent, you can pass in a TypedMessageBuilderCustomizer when sending a message.

## Receiving a Message

When the Apache Pulsar infrastructure is present, any bean can be annotated with @PulsarListener to create a listener endpoint.
The following component creates a listener endpoint on the someTopic topic:

-
Java

-
Kotlin

```
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

@Component
public class MyBean {

	@PulsarListener(topics = "someTopic")
	public void processMessage(String content) {
		// ...
	}

}
```

```
import org.springframework.pulsar.annotation.PulsarListener
import org.springframework.stereotype.Component

@Component
class MyBean {

	@PulsarListener(topics = ["someTopic"])
	fun processMessage(content: String?) {
		// ...
	}

}
```

Spring Boot auto-configuration provides all the components necessary for PulsarListener, such as the PulsarListenerContainerFactory and the consumer factory it uses to construct the underlying Pulsar consumers.
You can configure these components by specifying any of the spring.pulsar.listener.* and spring.pulsar.consumer.* prefixed application properties.

If you need more control over the configuration of the consumer factory, consider registering one or more ConsumerBuilderCustomizer beans.
These customizers are applied to all consumers created by the factory, and therefore all @PulsarListener instances.
You can also customize a single listener by setting the consumerCustomizer attribute of the @PulsarListener annotation.

If you need more control over the actual container factory configuration, consider registering one or more PulsarContainerFactoryCustomizer<ConcurrentPulsarListenerContainerFactory<?>> beans.

## Reading a Message

The Pulsar reader interface enables applications to manually manage cursors.
When you use a reader to connect to a topic you need to specify which message the reader begins reading from when it connects to a topic.

When the Apache Pulsar infrastructure is present, any bean can be annotated with @PulsarReader to consume messages using a reader.
The following component creates a reader endpoint that starts reading messages from the beginning of the someTopic topic:

-
Java

-
Kotlin

```
import org.springframework.pulsar.annotation.PulsarReader;
import org.springframework.stereotype.Component;

@Component
public class MyBean {

	@PulsarReader(topics = "someTopic", startMessageId = "earliest")
	public void processMessage(String content) {
		// ...
	}

}
```

```
import org.springframework.pulsar.annotation.PulsarReader
import org.springframework.stereotype.Component

@Component
class MyBean {

	@PulsarReader(topics = ["someTopic"], startMessageId = "earliest")
	fun processMessage(content: String?) {
		// ...
	}

}
```

The @PulsarReader relies on a PulsarReaderFactory to create the underlying Pulsar reader.
Spring Boot auto-configuration provides this reader factory which can be customized by setting any of the spring.pulsar.reader.* prefixed application properties.

If you need more control over the configuration of the reader factory, consider registering one or more ReaderBuilderCustomizer beans.
These customizers are applied to all readers created by the factory, and therefore all @PulsarReader instances.
You can also customize a single listener by setting the readerCustomizer attribute of the @PulsarReader annotation.

If you need more control over the actual container factory configuration, consider registering one or more PulsarContainerFactoryCustomizer<DefaultPulsarReaderContainerFactory<?>> beans.

For more details on any of the above components and to discover other available features, see the Spring for Apache Pulsar reference documentation.

## Transaction Support

Spring for Apache Pulsar supports transactions when using PulsarTemplate and @PulsarListener.

Setting the spring.pulsar.transaction.enabled property to true will:

-
Configure a PulsarTransactionManager bean

-
Enable transaction support for PulsarTemplate

-
Enable transaction support for @PulsarListener methods

The transactional attribute of @PulsarListener can be used to fine-tune when transactions should be used with listeners.

For more control of the Spring for Apache Pulsar transaction features you should define your own PulsarTemplate and/or ConcurrentPulsarListenerContainerFactory beans.
You can also define a PulsarAwareTransactionManager bean if the default auto-configured PulsarTransactionManager is not suitable.

## Additional Pulsar Properties

The properties supported by auto-configuration are shown in the Integration Properties section of the Appendix.
Note that, for the most part, these properties (hyphenated or camelCase) map directly to the Apache Pulsar configuration properties.
See the Apache Pulsar documentation for details.

Only a subset of the properties supported by Pulsar are available directly through the PulsarProperties class.
If you wish to tune the auto-configured components with additional properties that are not directly supported, you can use the customizer supported by each aforementioned component.