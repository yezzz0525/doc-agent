<!-- 来源: https://docs.spring.io/spring-boot/reference/packaging/efficient.html -->

# Efficient Deployments

## Unpacking the Executable jar

You can run your application using the executable jar, but loading the classes from nested jars has a small startup cost.
Depending on the size of the jar, running the application from an exploded structure is faster and recommended in production.
Certain PaaS implementations may also choose to extract archives before they run.
For example, Cloud Foundry operates this way.

Spring Boot supports extracting your application to a directory using different layouts.
The default layout is the most efficient, and it is AOT cache (and CDS) friendly.

In this layout, the libraries are extracted to a lib/ folder, and the application jar
contains the application classes and a manifest which references the libraries in the lib/ folder.

To unpack the executable jar, run this command:

```
$ java -Djarmode=tools -jar my-app.jar extract
```

And then in production, you can run the extracted jar:

```
$ java -jar my-app/my-app.jar
```

After startup, you should not expect any differences in execution time between running an executable jar and running an extracted jar.

Run java -Djarmode=tools -jar my-app.jar help extract to see all possible options.