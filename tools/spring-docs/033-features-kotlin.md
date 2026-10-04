<!-- 来源: https://docs.spring.io/spring-boot/reference/features/kotlin.html -->

# Kotlin Support

Kotlin is a statically-typed language targeting the JVM (and other platforms) which allows writing concise and elegant code while providing interoperability with existing libraries written in Java.

Spring Boot provides Kotlin support by leveraging the support in other Spring projects such as Spring Framework, Spring Data, and Reactor.
See the Spring Framework Kotlin support documentation for more information.

The easiest way to start with Spring Boot and Kotlin is to follow this comprehensive tutorial.
You can create new Kotlin projects by using start.spring.io.
Feel free to join the #spring channel of Kotlin Slack or ask a question with the spring and kotlin tags on Stack Overflow if you need support.

## Requirements

Spring Boot requires at least Kotlin 2.2.x and manages a suitable Kotlin version through dependency management.
To use Kotlin, org.jetbrains.kotlin:kotlin-stdlib and org.jetbrains.kotlin:kotlin-reflect must be present on the classpath.

Kotlin 2.2.x introduces new defaulting rules for propagating annotations to parameters, fields, and properties. In order to avoid related warnings and use what will likely become the Kotlin default behavior in an upcoming version, it is recommended to configure the -Xannotation-default-target=param-property compiler flag.

Since Kotlin classes are final by default, you are likely to want to configure kotlin-spring plugin in order to automatically open Spring-annotated classes so that they can be proxied.

Jackson’s Kotlin module is required for serializing / deserializing JSON data in Kotlin.
It is automatically registered when found on the classpath.

These dependencies and plugins are provided by default if one bootstraps a Kotlin project on start.spring.io.

## Null-safety

One of Kotlin’s key features is null-safety.
It deals with null values at compile time rather than deferring the problem to runtime and encountering a NullPointerException.
This helps to eliminate a common source of bugs without paying the cost of wrappers like Optional.
Kotlin also allows using functional constructs with nullable values as described in this comprehensive guide to null-safety in Kotlin.

Although Java does not let you express null-safety in its type-system, most Spring projects
provide null-safety via JSpecify annotations.

As of Kotlin 2.1, Kotlin enforces strict handling of nullability annotations from the org.jspecify.annotations package.

## Kotlin API

### runApplication

Spring Boot provides an idiomatic way to run an application with runApplication<MyApplication>(*args) as shown in the following example:

```
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MyApplication

fun main(args: Array<String>) {
	runApplication<MyApplication>(*args)
}
```

This is a drop-in replacement for SpringApplication.run(MyApplication::class.java, *args).
It also allows customization of the application as shown in the following example:

```
runApplication<MyApplication>(*args) {
	setBannerMode(OFF)
}
```

### Extensions

Kotlin extensions provide the ability to extend existing classes with additional functionality.
The Spring Boot Kotlin API makes use of these extensions to add new Kotlin specific conveniences to existing APIs.

TestRestTemplate extensions, similar to those provided by Spring Framework for RestOperations in Spring Framework, are provided.
Among other things, the extensions make it possible to take advantage of Kotlin reified type parameters.

## Dependency Management

In order to avoid mixing different versions of Kotlin dependencies on the classpath, Spring Boot imports the Kotlin BOM.

With Maven, the Kotlin version can be customized by setting the kotlin.version property and plugin management is provided for kotlin-maven-plugin.
With Gradle, the Spring Boot plugin automatically aligns the kotlin.version with the version of the Kotlin plugin.

Spring Boot also manages the version of Coroutines dependencies by importing the Kotlin Coroutines BOM.
The version can be customized by setting the kotlin-coroutines.version property.

org.jetbrains.kotlinx:kotlinx-coroutines-reactor dependency is provided by default if one bootstraps a Kotlin project with at least one reactive dependency on start.spring.io.

## @ConfigurationProperties

@ConfigurationProperties when used in combination with constructor binding supports data classes with immutable val properties as shown in the following example:

```
@ConfigurationProperties("example.kotlin")
data class KotlinExampleProperties(
		val name: String,
		val description: String,
		val myService: MyService
) {

	data class MyService(
			val apiToken: String,
			val uri: URI
	)
}
```

Due to the limitations of their interoperability with Java, support for value classes is limited.
In particular, relying upon a value class’s default value will not work with configuration property binding.
In such cases, a data class should be used instead.

To generate your own metadata using the annotation processor, kapt should be configured with the spring-boot-configuration-processor dependency.
Note that some features (such as detecting the default value or deprecated items) are not working due to limitations in the model kapt provides.

## Testing

While it is possible to use JUnit 4 to test Kotlin code, JUnit 6 is provided by default and is recommended.
JUnit 6 enables a test class to be instantiated once and reused for all of the class’s tests.
This makes it possible to use @BeforeAll and @AfterAll annotations on non-static methods, which is a good fit for Kotlin.

To mock Kotlin classes, MockK is recommended.
If you need the MockK equivalent of the Mockito specific @MockitoBean and @MockitoSpyBean annotations, you can use SpringMockK which provides similar @MockkBean and @SpykBean annotations.

## Resources

### Further Reading

-
Kotlin language reference

-
Kotlin Slack (with a dedicated #spring channel)

-
Stack Overflow with spring and kotlin tags

-
Try Kotlin in your browser

-
Kotlin blog

-
Awesome Kotlin

-
Tutorial: building web applications with Spring Boot and Kotlin

-
Developing Spring Boot applications with Kotlin

-
A Geospatial Messenger with Kotlin, Spring Boot and PostgreSQL

-
Introducing Kotlin support in Spring Framework 5.0

-
Spring Framework 5 Kotlin APIs, the functional way

### Examples

-
spring-boot-kotlin-demo: regular Spring Boot + Spring Data JPA project

-
mixit: Spring Boot 2 + WebFlux + Reactive Spring Data MongoDB

-
spring-kotlin-fullstack: WebFlux Kotlin fullstack example with Kotlin2js for frontend instead of JavaScript or TypeScript

-
spring-petclinic-kotlin: Kotlin version of the Spring PetClinic Sample Application

-
spring-kotlin-deepdive: a step by step migration for Boot 1.0 + Java to Boot 2.0 + Kotlin

-
spring-boot-coroutines-demo: Coroutines sample project