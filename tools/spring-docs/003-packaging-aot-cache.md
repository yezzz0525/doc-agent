<!-- 来源: https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html -->

# AOT Cache

AOT cache is a JVM feature that can help reduce the startup time and memory footprint of Java applications.

If you are not yet using Java 25 or above, you should read the sections about CDS.
CDS is the predecessor of AOT cache, but works similarly.

Spring Boot supports both CDS and AOT cache, however, we recommend using the AOT cache whenever possible.

## AOT Cache

Spring Boot supports the AOT cache for Java 25 and above.
If you’re using an earlier version of Java, you have to use CDS instead.

To use the AOT cache feature, you should first perform a training run on your application in extracted form:

```
$ java -Djarmode=tools -jar my-app.jar extract --destination application
$ cd application
$ java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar my-app.jar
```

This creates an app.aot cache file that can be reused as long as the application is not updated and the same Java version is used.

To use the cache file, you need to add an extra parameter when starting the application:

```
$ java -XX:AOTCache=app.aot -jar my-app.jar
```

You have to use the cache file with the extracted form of the application, otherwise it has no effect.

If you want to know how to leverage AOT Cache with Buildpacks, take a look at the AOT Cache How-to guide.

## CDS

If you’re using Java 25 or above, please use AOT cache instead of CDS.

To use CDS, you should first perform a training run on your application in extracted form:

```
$ java -Djarmode=tools -jar my-app.jar extract --destination application
$ cd application
$ java -XX:ArchiveClassesAtExit=application.jsa -Dspring.context.exit=onRefresh -jar my-app.jar
```

This creates an application.jsa archive file that can be reused as long as the application is not updated.

To use the archive file, you need to add an extra parameter when starting the application:

```
$ java -XX:SharedArchiveFile=application.jsa -jar my-app.jar
```

You have to use the cache file with the extracted form of the application, otherwise it has no effect.

For more details about CDS, refer to the Class Data Sharing documentation of the JDK.