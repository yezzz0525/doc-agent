<!-- 来源: https://docs.spring.io/spring-boot/reference/messaging/amqp.html -->

# AMQP

The Advanced Message Queuing Protocol (AMQP) is a platform-neutral, wire-level protocol for message-oriented middleware.
The Spring AMQP project applies core Spring concepts to the development of AMQP-based messaging solutions.
Spring Boot offers several conveniences for working with AMQP through RabbitMQ, including the spring-boot-starter-amqp starter.

## RabbitMQ Support

RabbitMQ is a lightweight, reliable, scalable, and portable message broker based on the AMQP protocol.
Spring uses RabbitMQ to communicate through the AMQP protocol.

RabbitMQ configuration is controlled by external configuration properties in spring.rabbitmq.*.
For example, you might declare the following section in application.properties:

-
Properties

-
YAML

```
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672
spring.rabbitmq.username=admin
spring.rabbitmq.password=secret
```

```
spring:
  rabbitmq:
    host: "localhost"
    port: 5672
    username: "admin"
    password: "secret"
```

Alternatively, you could configure the same connection using the addresses attribute:

-
Properties

-
YAML

```
spring.rabbitmq.addresses=amqp://admin:secret@localhost
```

```
spring:
  rabbitmq:
    addresses: "amqp://admin:secret@localhost"
```

When specifying addresses that way, the host and port properties are ignored.
If the address uses the amqps protocol, SSL support is enabled automatically.

See RabbitProperties for more of the supported property-based configuration options.
To configure lower-level details of the RabbitMQ ConnectionFactory that is used by Spring AMQP, define a ConnectionFactoryCustomizer bean.

If a ConnectionNameStrategy bean exists in the context, it will be automatically used to name connections created by the auto-configured CachingConnectionFactory.

To make an application-wide, additive customization to the RabbitTemplate, use a RabbitTemplateCustomizer bean.

See Understanding AMQP, the protocol used by RabbitMQ for more details.

## Sending a Message

Spring’s AmqpTemplate and AmqpAdmin are auto-configured, and you can autowire them directly into your own beans, as shown in the following example:

-
Java

-
Kotlin

```
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Component;

@Component
public class MyBean {

	private final AmqpAdmin amqpAdmin;

	private final AmqpTemplate amqpTemplate;

	public MyBean(AmqpAdmin amqpAdmin, AmqpTemplate amqpTemplate) {
		this.amqpAdmin = amqpAdmin;
		this.amqpTemplate = amqpTemplate;
	}

	// ...

	public void someMethod() {
		this.amqpAdmin.getQueueInfo("someQueue");
	}

	public void someOtherMethod() {
		this.amqpTemplate.convertAndSend("hello");
	}

}
```

```
import org.springframework.amqp.core.AmqpAdmin
import org.springframework.amqp.core.AmqpTemplate
import org.springframework.stereotype.Component

@Component
class MyBean(private val amqpAdmin: AmqpAdmin, private val amqpTemplate: AmqpTemplate) {

	// ...

	fun someMethod() {
		amqpAdmin.getQueueInfo("someQueue")
	}

	fun someOtherMethod() {
		amqpTemplate.convertAndSend("hello")
	}

}
```

RabbitMessagingTemplate can be injected in a similar manner.
If a MessageConverter bean is defined, it is associated automatically to the auto-configured AmqpTemplate.

If necessary, any Queue that is defined as a bean is automatically used to declare a corresponding queue on the RabbitMQ instance.

To retry operations, you can enable retries on the AmqpTemplate (for example, in the event that the broker connection is lost):

-
Properties

-
YAML

```
spring.rabbitmq.template.retry.enabled=true
spring.rabbitmq.template.retry.initial-interval=2s
```

```
spring:
  rabbitmq:
    template:
      retry:
        enabled: true
        initial-interval: "2s"
```

Retries are disabled by default.
You can also customize the RetryTemplate programmatically by declaring a RabbitTemplateRetrySettingsCustomizer bean.

If you need to create more RabbitTemplate instances or if you want to override the default, Spring Boot provides a RabbitTemplateConfigurer bean that you can use to initialize a RabbitTemplate with the same settings as the factories used by the auto-configuration.

If there’s a bean of type RabbitTemplateObservationConvention in the context, it will automatically be configured on the RabbitTemplate.

## Sending a Message To A Stream

To send a message to a particular stream, specify the name of the stream, as shown in the following example:

-
Properties

-
YAML

```
spring.rabbitmq.stream.name=my-stream
```

```
spring:
  rabbitmq:
    stream:
      name: "my-stream"
```

If a MessageConverter, StreamMessageConverter, ProducerCustomizer or RabbitStreamTemplateObservationConvention bean is defined, it is associated automatically to the auto-configured RabbitStreamTemplate.

If you need to create more RabbitStreamTemplate instances or if you want to override the default, Spring Boot provides a RabbitStreamTemplateConfigurer bean that you can use to initialize a RabbitStreamTemplate with the same settings as the factories used by the auto-configuration.

### SSL

To use SSL with RabbitMQ Streams, set spring.rabbitmq.stream.ssl.enabled to true or set spring.rabbitmq.stream.ssl.bundle to configure the SSL bundle to use.

## Receiving a Message

When the Rabbit infrastructure is present, any bean can be annotated with @RabbitListener to create a listener endpoint.
If no RabbitListenerContainerFactory has been defined, a default SimpleRabbitListenerContainerFactory is automatically configured and you can switch to a direct container using the spring.rabbitmq.listener.type property.
If a MessageConverter, a MessageRecoverer or a RabbitListenerObservationConvention bean is defined, it is automatically associated with the default factory.

The following sample component creates a listener endpoint on the someQueue queue:

-
Java

-
Kotlin

```
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class MyBean {

	@RabbitListener(queues = "someQueue")
	public void processMessage(String content) {
		// ...
	}

}
```

```
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class MyBean {

	@RabbitListener(queues = ["someQueue"])
	fun processMessage(content: String?) {
		// ...
	}

}
```

See @EnableRabbit for more details.

If you need to create more RabbitListenerContainerFactory instances or if you want to override the default, Spring Boot provides a SimpleRabbitListenerContainerFactoryConfigurer and a DirectRabbitListenerContainerFactoryConfigurer that you can use to initialize a SimpleRabbitListenerContainerFactory and a DirectRabbitListenerContainerFactory with the same settings as the factories used by the auto-configuration.

It does not matter which container type you chose.
Those two beans are exposed by the auto-configuration.

For instance, the following configuration class exposes another factory that uses a specific MessageConverter:

-
Java

-
Kotlin

```
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyRabbitConfiguration {

	@Bean
	public SimpleRabbitListenerContainerFactory myFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		ConnectionFactory connectionFactory = getCustomConnectionFactory();
		configurer.configure(factory, connectionFactory);
		factory.setMessageConverter(new MyMessageConverter());
		return factory;
	}

	private ConnectionFactory getCustomConnectionFactory() {
		return ...
	}

}
```

```
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyRabbitConfiguration {

	@Bean
	fun myFactory(configurer: SimpleRabbitListenerContainerFactoryConfigurer): SimpleRabbitListenerContainerFactory {
		val factory = SimpleRabbitListenerContainerFactory()
		val connectionFactory = getCustomConnectionFactory()
		configurer.configure(factory, connectionFactory)
		factory.setMessageConverter(MyMessageConverter())
		return factory
	}

	fun getCustomConnectionFactory() : ConnectionFactory {
		return ...
	}

}
```

Then you can use the factory in any @RabbitListener-annotated method, as follows:

-
Java

-
Kotlin

```
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class MyBean {

	@RabbitListener(queues = "someQueue", containerFactory = "myFactory")
	public void processMessage(String content) {
		// ...
	}

}
```

```
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class MyBean {

	@RabbitListener(queues = ["someQueue"], containerFactory = "myFactory")
	fun processMessage(content: String?) {
		// ...
	}

}
```

You can enable retries to handle situations where your listener throws an exception.
By default, RejectAndDontRequeueRecoverer is used, but you can define a MessageRecoverer of your own.
When retries are exhausted, the message is rejected and either dropped or routed to a dead-letter exchange if the broker is configured to do so.
By default, retries are disabled.
You can also customize the RetryPolicy programmatically by declaring a RabbitListenerRetrySettingsCustomizer bean.

By default, if retries are disabled and the listener throws an exception, the delivery is retried indefinitely.
You can modify this behavior in two ways: Set the defaultRequeueRejected property to false so that zero re-deliveries are attempted or throw an AmqpRejectAndDontRequeueException to signal the message should be rejected.
The latter is the mechanism used when retries are enabled and the maximum number of delivery attempts is reached.