package net.chrisrichardson.ftgo.orderservice.main;

import io.eventuate.tram.jdbckafka.TramJdbcKafkaConfiguration;
import io.microservices.canvas.extractor.spring.annotations.ServiceDescription;
import io.microservices.canvas.springmvc.MicroserviceCanvasWebConfiguration;
import net.chrisrichardson.eventstore.examples.customersandorders.commonswagger.CommonSwaggerConfiguration;
import net.chrisrichardson.ftgo.orderservice.grpc.GrpcConfiguration;
import net.chrisrichardson.ftgo.orderservice.messaging.OrderServiceMessagingConfiguration;
import net.chrisrichardson.ftgo.orderservice.service.OrderCommandHandlersConfiguration;
import net.chrisrichardson.ftgo.orderservice.web.OrderWebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/***
https://www.baeldung.com/spring-boot-migration

Each application built using Spring Boot needs to define the main entry point. 
This is usually a Java class with the main method, annotated with @SpringBootApplication:

The @SpringBootApplication annotation adds the following annotations:

	@SpringBootConfiguration ¡ªDesignates this class as a configuration class. 
	This annotation is, in fact, a specialized form of the @Configuration annotation. @Configuration which marks the class as a source of bean definitions
	
	@EnableAutoConfiguration ¨C which tells the framework to add beans based on the dependencies on the classpath automatically. 
	In other words, this annotation tells Spring Boot to automatically configure any components that it thinks you¡¯ll need. 
	
	@ComponentScan ¨C  Enables component scanning. This lets you declare other classes with annotations 
	like @Component, @Controller, @Service, and others, to have Spring automatically discover them and register them as components in the Spring application context.
	SpringBoot scans for other configurations and beans in the same package as the Application class or below. 
	
By default, the @SpringBootApplication annotation scans all classes in the same package or below. 
Therefore, a convenient package structure could look like this:	

To import the classes explicitly, you can use the @ComponentScan or @Import annotations on the main class:

https://www.journaldev.com/21033/spring-configuration-annotation

Spring @Configuration annotation is part of the spring core framework. 
Spring Configuration annotation indicates that the class has @Bean definition methods. 
So Spring container can process the class and generate Spring Beans to be used in the application.

***/
@SpringBootApplication
@Import({OrderWebConfiguration.class, 
	OrderCommandHandlersConfiguration.class,  
	OrderServiceMessagingConfiguration.class,
	/***
	Create messageProducer and messageConsumer ? 
	From application.properties and TramMessageProducerJdbcConfiguration,
	you can see that
	spring.datasource.driver-class-name=com.mysql.jdbc.Driver

	mysql is used as a messageProducer. 
	
	From SagaManagerImpl.processActions,
	you can see 
	
		sagaCommandProducer.sendCommands
			commandProducer.send
			    messageProducer.send(channel, message);

 These CreateTicket commands is sent to a mysql table owned by OrderService, 
 Check template.sql, there is a table called message there. 
 
 I guess KitcheService must subscribe this table somewhere.
	
	***/
    TramJdbcKafkaConfiguration.class, 
    CommonSwaggerConfiguration.class, 
    GrpcConfiguration.class,
    MicroserviceCanvasWebConfiguration.class})
@ServiceDescription(description="Manages Orders", capabilities = "Order Management")
public class AnnotatedOrderServiceMain {

  public static void main(String[] args) {
	 /***
	The other important piece of TacoCloudApplication is the main() method. This is the
	method that will be run when the JAR file is executed. For the most part, this method is
	boilerplate code; every Spring Boot application you write will have a method similar or
	identical to this one (class name differences notwithstanding).
	The main() method calls a static run() method on the SpringApplication class,
	which performs the actual bootstrapping of the application, creating the Spring application
	context. The two parameters passed to the run() method are a configuration
	class and the command-line arguments. Although it¡¯s not necessary that the configuration
	class passed to run() be the same as the bootstrap class, this is the most convenient
	and typical choice.	  
	***/
    SpringApplication.run(AnnotatedOrderServiceMain.class, args);
  }
}
