<!-- 来源: https://docs.spring.io/spring-boot/reference/using/using-the-springbootapplication-annotation.html -->

# Using the @SpringBootApplication Annotation

Many Spring Boot developers like their apps to use auto-configuration, component scan and be able to define extra configuration on their "application class".
A single @SpringBootApplication annotation can be used to enable those three features, that is:

-
@EnableAutoConfiguration: enable Spring Boot’s auto-configuration mechanism

-
@ComponentScan: enable @Component scan on the package where the application is located (see the best practices)

-
@SpringBootConfiguration: enable registration of extra beans in the context or the import of additional configuration classes.
An alternative to Spring’s standard @Configuration that aids configuration detection in your integration tests.

-
Java

-
Kotlin

```
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Same as @SpringBootConfiguration @EnableAutoConfiguration @ComponentScan
@SpringBootApplication
public class MyApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyApplication.class, args);
	}

}
```

```
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// Same as @SpringBootConfiguration @EnableAutoConfiguration @ComponentScan
@SpringBootApplication
class MyApplication

fun main(args: Array<String>) {
	runApplication<MyApplication>(*args)
}
```

@SpringBootApplication also provides aliases to customize the attributes of @EnableAutoConfiguration and @ComponentScan.

None of these features are mandatory and you may choose to replace this single annotation by any of the features that it enables.
For instance, you may not want to use component scan or configuration properties scan in your application:

-
Java

-
Kotlin

```
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({ SomeConfiguration.class, AnotherConfiguration.class })
public class MyApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyApplication.class, args);
	}

}
```

```
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.docs.using.structuringyourcode.locatingthemainclass.MyApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(SomeConfiguration::class, AnotherConfiguration::class)
class MyApplication

fun main(args: Array<String>) {
	runApplication<MyApplication>(*args)
}
```

In this example, MyApplication is just like any other Spring Boot application except that @Component-annotated classes and @ConfigurationProperties-annotated classes are not detected automatically and the user-defined beans are imported explicitly (see @Import).