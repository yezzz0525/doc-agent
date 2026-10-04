<!-- 来源: https://docs.spring.io/spring-boot/reference/io/webservices.html -->

# Web Services

Spring Boot provides Web Services auto-configuration so that all you must do is define your @Endpoint beans.

The Spring Web Services features can be easily accessed with the spring-boot-starter-webservices module.

SimpleWsdl11Definition and SimpleXsdSchema beans can be automatically created for your WSDLs and XSDs respectively.
To do so, configure their location, as shown in the following example:

-
Properties

-
YAML

```
spring.webservices.wsdl-locations=classpath:/wsdl
```

```
spring:
  webservices:
    wsdl-locations: "classpath:/wsdl"
```

## Calling Web Services with WebServiceTemplate

If you need to call remote Web services from your application, you can use the WebServiceTemplate class.
Since WebServiceTemplate instances often need to be customized before being used, Spring Boot does not provide any single auto-configured WebServiceTemplate bean.
It does, however, auto-configure a WebServiceTemplateBuilder, which can be used to create WebServiceTemplate instances when needed.

The following code shows a typical example:

-
Java

-
Kotlin

```
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.core.SoapActionCallback;

@Service
public class MyService {

	private final WebServiceTemplate webServiceTemplate;

	public MyService(WebServiceTemplateBuilder webServiceTemplateBuilder) {
		this.webServiceTemplate = webServiceTemplateBuilder.build();
	}

	public SomeResponse someWsCall(SomeRequest detailsReq) {
		return (SomeResponse) this.webServiceTemplate.marshalSendAndReceive(detailsReq,
				new SoapActionCallback("https://ws.example.com/action"));
	}

}
```

```
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.ws.client.core.WebServiceTemplate
import org.springframework.ws.soap.client.core.SoapActionCallback

@Service
class MyService(webServiceTemplateBuilder: WebServiceTemplateBuilder) {

	private val webServiceTemplate: WebServiceTemplate

	init {
		webServiceTemplate = webServiceTemplateBuilder.build()
	}

	fun someWsCall(detailsReq: SomeRequest): SomeResponse {
		return webServiceTemplate.marshalSendAndReceive(
			detailsReq,
			SoapActionCallback("https://ws.example.com/action")
		) as SomeResponse
	}

}
```

By default, WebServiceTemplateBuilder detects a suitable HTTP-based WebServiceMessageSender using the available HTTP client libraries on the classpath.
You can also customize read and connection timeouts for an individual builder as follows:

-
Java

-
Kotlin

```
import java.time.Duration;

import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.webservices.client.WebServiceMessageSenderFactory;
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ws.client.core.WebServiceTemplate;

@Configuration(proxyBeanMethods = false)
public class MyWebServiceTemplateConfiguration {

	@Bean
	public WebServiceTemplate webServiceTemplate(WebServiceTemplateBuilder builder) {
		HttpClientSettings settings = HttpClientSettings.defaults()
			.withConnectTimeout(Duration.ofSeconds(2))
			.withReadTimeout(Duration.ofSeconds(2));
		builder.httpMessageSenderFactory(WebServiceMessageSenderFactory.http(settings));
		return builder.build();
	}

}
```

```
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.webservices.client.WebServiceMessageSenderFactory
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.ws.client.core.WebServiceTemplate
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class MyWebServiceTemplateConfiguration {

	@Bean
	fun webServiceTemplate(builder: WebServiceTemplateBuilder): WebServiceTemplate {
		val settings = HttpClientSettings.defaults()
				.withConnectTimeout(Duration.ofSeconds(2))
				.withReadTimeout(Duration.ofSeconds(2))
		builder.httpMessageSenderFactory(WebServiceMessageSenderFactory.http(settings))
		return builder.build()
	}

}
```

You can also change the global HTTP client configuration used if not specific template customization code is applied.