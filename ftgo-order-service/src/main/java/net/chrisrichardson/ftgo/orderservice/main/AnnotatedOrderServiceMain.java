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

	@Configuration ¨C which marks the class as a source of bean definitions
	@EnableAutoConfiguration ¨C which tells the framework to add beans based on the dependencies on the classpath automatically
	@ComponentScan ¨C which scans for other configurations and beans in the same package as the Application class or below
	
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
    SpringApplication.run(AnnotatedOrderServiceMain.class, args);
  }
}
