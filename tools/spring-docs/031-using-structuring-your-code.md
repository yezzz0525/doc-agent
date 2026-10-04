<!-- 来源: https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html -->

# Structuring Your Code

Spring Boot does not require any specific code layout to work.
However, there are some best practices that help.

If you wish to enforce a structure based on domains, take a look at Spring Modulith.

## Using the “default” Package

When a class does not include a package declaration, it is considered to be in the “default package”.
The use of the “default package” is generally discouraged and should be avoided.
It can cause particular problems for Spring Boot applications that use the @ComponentScan, @ConfigurationPropertiesScan, @EntityScan, or @SpringBootApplication annotations, since every class from every jar is read.

We recommend that you follow Java’s recommended package naming conventions and use a reversed domain name (for example, com.example.project).

## Locating the Main Application Class

We generally recommend that you locate your main application class in a root package above other classes.
The @SpringBootApplication annotation is often placed on your main class, and it implicitly defines a base “search package” for certain items.
For example, if you are writing a JPA application, the package of the @SpringBootApplication annotated class is used to search for @Entity items.
Using a root package also allows component scan to apply only on your project.

If you do not want to use @SpringBootApplication, the @EnableAutoConfiguration and @ComponentScan annotations that it imports defines that behavior so you can also use those instead.

The following listing shows a typical layout:

```
com
 +- example
     +- myapplication
         +- MyApplication.java
         |
         +- customer
         |   +- Customer.java
         |   +- CustomerController.java
         |   +- CustomerService.java
         |   +- CustomerRepository.java
         |
         +- order
             +- Order.java
             +- OrderController.java
             +- OrderService.java
             +- OrderRepository.java
```

The MyApplication.java file would declare the main method, along with the basic @SpringBootApplication, as follows:

-
Java

-
Kotlin

```
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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

@SpringBootApplication
class MyApplication

fun main(args: Array<String>) {
	runApplication<MyApplication>(*args)
}
```