package net.chrisrichardson.ftgo.orderservice.cucumber;

import cucumber.api.java.Before;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import io.eventuate.tram.jdbckafka.TramJdbcKafkaConfiguration;
import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.messaging.common.ChannelMapping;
import io.eventuate.tram.messaging.common.DefaultChannelMapping;
import io.eventuate.tram.messaging.consumer.MessageConsumer;
import io.eventuate.tram.sagas.testing.SagaParticipantChannels;
import io.eventuate.tram.sagas.testing.SagaParticipantStubManager;
import io.eventuate.tram.sagas.testing.SagaParticipantStubManagerConfiguration;
import io.eventuate.tram.testing.MessageTracker;
import io.restassured.response.Response;
import net.chrisrichardson.ftgo.accountservice.api.AuthorizeCommand;
import net.chrisrichardson.ftgo.common.CommonJsonMapperInitializer;
import net.chrisrichardson.ftgo.consumerservice.api.ValidateOrderByConsumer;
import net.chrisrichardson.ftgo.kitchenservice.api.CancelCreateTicket;
import net.chrisrichardson.ftgo.kitchenservice.api.ConfirmCreateTicket;
import net.chrisrichardson.ftgo.kitchenservice.api.CreateTicket;
import net.chrisrichardson.ftgo.kitchenservice.api.CreateTicketReply;
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother;
import net.chrisrichardson.ftgo.orderservice.RestaurantMother;
import net.chrisrichardson.ftgo.orderservice.api.web.CreateOrderRequest;
import net.chrisrichardson.ftgo.orderservice.domain.Order;
import net.chrisrichardson.ftgo.orderservice.domain.RestaurantRepository;
import net.chrisrichardson.ftgo.restaurantservice.events.RestaurantCreated;
import net.chrisrichardson.ftgo.testutil.FtgoTestUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.Arrays;
import java.util.Collections;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;
import static io.eventuate.util.test.async.Eventually.eventually;
import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_RESTAURANT_MENU;
import static org.junit.Assert.*;

/***
Imagine
that we now want to verify that Order Service works as expected. In other words,
we want to write the service¡¯s acceptance tests, which treat it as a black box and verify
its behavior through its API. One approach is to write what are essentially end-to-end
tests and deploy Order Service and all of its transitive dependencies. As you should
know by now, that¡¯s a slow, brittle, and expensive way to test a service.

A much better way to write acceptance tests for a service is to use component testing.
As figure 10.6 shows, component tests are sandwiched between integration tests and
end-to-end tests. Component testing verifies the behavior of a service in isolation. It
replaces a service¡¯s dependencies with stubs that simulate their behavior. It might even
use in-memory versions of infrastructure services such as databases. As a result, component
tests are much easier to write and faster to run.

But before a component test can execute the Gherkin scenarios, it must first run
Order Service and set up the service¡¯s dependencies. You need to test Order Service
in isolation, so the component test must configure stubs for several services, including
Kitchen Service. It also needs to set up a database and the messaging infrastructure.
There are a few different options that trade off realism with speed and simplicity.

IN-PROCESS COMPONENT TESTS
One option is to write in-process component tests. An in-process component test runs the
service with in-memory stubs and mocks for its dependencies. For example, you can
write a component test for a Spring Boot-based service using the Spring Boot testing
framework. A test class, which is annotated with @SpringBootTest, runs the service in
the same JVM as the test. It uses dependency injection to configure the service to use
mocks and stubs. For instance, a test for Order Service would configure it to use an
in-memory JDBC database, such as H2, HSQLDB, or Derby, and in-memory stubs for
Eventuate Tram. In-process tests are simpler to write and faster, but have the downside
of not testing the deployable service.

OUT-OF-PROCESS COMPONENT TESTING
A more realistic approach is to package the service in a production-ready format and
run it as a separate process. For example, chapter 12 explains that it¡¯s increasingly
common to package services as Docker container images. An out-of-process component
test uses real infrastructure services, such as databases and message brokers, but uses
stubs for any dependencies that are application services. For example, an out-of-process
component test for FTGO Order Service would use MySQL and Apache Kafka, and
stubs for services including Consumer Service and Accounting Service. Because Order
Service interacts with those services using messaging, these stubs would consume
messages from Apache Kafka and send back reply messages.

HOW TO STUB SERVICES IN OUT-OF-PROCESS COMPONENT TESTS
The service under test often invokes dependencies using interaction styles that involve
sending back a response. Order Service, for example, uses asynchronous request/
response and sends command messages to various services. API Gateway uses HTTP,
which is a request/response interaction style. An out-of-process test must configure
stubs for these kinds of dependencies, which handle requests and send back replies.
One option is to use Spring Cloud Contract, which we looked at earlier in section
10.1 when discussing integration tests. We could write contracts that configure
stubs for component tests. 

One thing to consider, though, is that it¡¯s likely that these
contracts, unlike those used for integration, would only be used by the component tests.
Another drawback of using Spring Cloud Contract for component testing is that
because its focus is consumer contract testing, it takes a somewhat heavyweight
approach. The JAR files containing the contracts must be deployed in a Maven
repository rather than merely being on the classpath. Handling interactions involving
dynamically generated values is also challenging. Consequently, a simpler option is to
configure stubs from within the test itself.
A test can, for example, configure an HTTP stub using the WireMock stubbing
DSL. Similarly, a test for a service that uses Eventuate Tram messaging can configure
messaging stubs. Later in this section I show an easy-to-use Java library that does this.
Now that we¡¯ve looked at how to design component tests, let¡¯s consider how to
write component tests for the FTGO Order Service.

ftgo-application-repo uses build-and-test-all.sh. build-and-test-all.sh runs the component tests. 
build-and-test-all.sh has 

	./gradlew :ftgo-order-service:cleanComponentTest :ftgo-order-service:componentTest
	
This command makes ftgo-order-service.build.gradle runs the component-test. It has

    componentTests {
        if (System.getenv("FTGO_DOCKER_COMPOSE_FILES") != null)
            useComposeFiles = System.getenv("FTGO_DOCKER_COMPOSE_FILES").split(",").collect { "../" + it }
        startedServices = [ 'ftgo-order-service']
        //forceRecreate = true
	stopContainers = true
    }
    
Basically, this makes ftgo-order-service executed in a docker container. Also, the related infrastructure services
e.g. mysql, messaging is also executed in docker containers ( they are in separate containers). but other dependent services
for order service, e.g. kitchen service, accounting service are run in stub and mock way. 

***/


/***
Executing Gherkin specification (place-order.feature) using Cucumber.
Cucumber is automated testing framework that executes tests written in Gherkin.
A StepDefinition class consists of methods that define the meaning of each
given-then-when step. 

OrderServiceComponentTest has an @CucumberOption that specifies where to find the 
Gherkin feature file. OrderServiceComponentTest is also annotated with 
@RunWith(Cucumber.class) which tells Junit to use the cucumber test runner.
But unlike typical junit test class, OrderServiceComponentTest doesn't have any 
test method. Instead, OrderServiceComponentTest reads the Gherkin features and 
uses OrderServiceComponentTestStepDefinitions class to make Gherkin features
file executable. 

Despite OrderServiceComponentTestStepDefinitions is not being a test class, 
OrderServiceComponentTestStepDefinitions is still annotated with 
@ContextConfiguration
which is is part of sprint testing framework. 
@ContextConfiguration creates Spring ApplicationContext which defines
the various Spring components including messaging stubs. 

OrderServiceComponentTestStepDefinitions is the heart of component testing.
It defines the meaning of each step in OrderService's component tests.

***/
@SpringBootTest(classes = AnnotatedOrderServiceComponentTestStepDefinitions.TestConfiguration.class, 
webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration
public class AnnotatedOrderServiceComponentTestStepDefinitions {

  private Response response;
  private long consumerId;

  static {
    CommonJsonMapperInitializer.registerMoneyModule();
  }

  private int port = 8082;
  private String host = FtgoTestUtil.getDockerHostIp();

  protected String baseUrl(String path) {
    return String.format("http://%s:%s%s", host, port, path);
  }

  @Configuration
  @EnableAutoConfiguration
  @Import({TramJdbcKafkaConfiguration.class, SagaParticipantStubManagerConfiguration.class})
  @EnableJpaRepositories(basePackageClasses = RestaurantRepository.class) // Need to verify that the restaurant has been created. Replace with verifyRestaurantCreatedInOrderService
  @EntityScan(basePackageClasses = Order.class)
  public static class TestConfiguration {

    @Bean
    public SagaParticipantChannels sagaParticipantChannels() {
      return new SagaParticipantChannels("consumerService", "kitchenService", "accountingService", "orderService");
    }

    @Bean
    public MessageTracker messageTracker(MessageConsumer messageConsumer) {
      return new MessageTracker(singleton("net.chrisrichardson.ftgo.orderservice.domain.Order"), messageConsumer) ;
    }
    

  }

  @Autowired
  protected SagaParticipantStubManager sagaParticipantStubManager;

  @Autowired
  protected MessageTracker messageTracker;

  @Autowired
  protected DomainEventPublisher domainEventPublisher;

  @Autowired
  protected RestaurantRepository restaurantRepository;


  @Before
  public void setUp() {
    sagaParticipantStubManager.reset();
  }

  /// the setup phase
  @Given("A valid consumer")
  public void useConsumer() {
	/// consumerServie here is a command channel
	/// orderService sends command to consumerService command channel
	/// Since we don't have a real cosumerService in component testing,
	/// sagaParticipantStubManager is going to play as a consumer service by
	/// sending back a success reply. 
	  
	/***
	If you check SagaManagerImpl and CreateOrderSaga, CreateOrderSaga uses *Proxy classes 
	to send the command to remote services. These remote services send reply message back.
	Now since we don't have these remote services in component testing, we ask 
	sagaParticipantStubManager to act as a remote service. 
	***/
    sagaParticipantStubManager.
            forChannel("consumerService")
            .when(ValidateOrderByConsumer.class).replyWith(cm -> withSuccess());
  }

  public enum CreditCardType { valid, expired}
  
  /// the setup phase
  @Given("using a(.?) (.*) credit card")
  public void useCreditCard(String ignore, CreditCardType creditCard) {
	  /***
	This method uses the SagaParticipantStubManager class, a test helper class that configures
	stubs for saga participants. The useCreditCard() method uses it to configure
	the Accounting Service stub to reply with either a success or a failure message,
	depending on the specified credit card.
	  
	   ***/
    switch (creditCard) {
      case valid : /// 1. send a success reply.
        sagaParticipantStubManager
                .forChannel("accountingService")
                .when(AuthorizeCommand.class).replyWithSuccess();
        break;
      case expired:
        sagaParticipantStubManager /// a test helper class that configures stubs for saga participants.
                .forChannel("accountingService") /// 2. send a failure reply.
                .when(AuthorizeCommand.class).replyWithFailure();
        break;
      default:
        fail("Don't know what to do with this credit card");
    }
  }
  /// the setup phase
  /***
  OrderServiceComponent uses both place-order.feature and OrderServiceComponentTestStepDefinition to 
  do the testing. 
  OrderServiceComponent reads a sentence from place-order.feature and then search that sentence in 
  OrderServiceComponentTestStepDefinition which is annotated with a method. 
  Then OrderServiceComponent will invoke that method to either set up the testing environment or to
  run the test. 
  
  ***/
  @Given("the restaurant is accepting orders")
  public void restaurantAcceptsOrder() {
    sagaParticipantStubManager
            .forChannel("kitchenService")
            .when(CreateTicket.class).replyWith(cm -> withSuccess(new CreateTicketReply(cm.getCommand().getOrderId())))
            .when(ConfirmCreateTicket.class).replyWithSuccess()
            .when(CancelCreateTicket.class).replyWithSuccess();

    if (!restaurantRepository.findById(RestaurantMother.AJANTA_ID).isPresent()) {
      domainEventPublisher.publish("net.chrisrichardson.ftgo.restaurantservice.domain.Restaurant", RestaurantMother.AJANTA_ID,
              Collections.singletonList(new RestaurantCreated(RestaurantMother.AJANTA_RESTAURANT_NAME, AJANTA_RESTAURANT_MENU)));

      eventually(() -> {
        FtgoTestUtil.assertPresent(restaurantRepository.findById(RestaurantMother.AJANTA_ID));
      });
    }
  }

  ///the excute phase
  /// this simulates what request and where this request is sent to when placing an order.
  /***
  Because orderService is really running at a docker container, and also we configure sagaParticipantStubManager,
  when we send this request, we are expecting orderService will send back a response. 
  Which is the return value of placeOrder method.
  but we didn't check this value here,
  we check the value in  theOrderShouldBeInState method.
  ***/
  @When("I place an order for Chicken Vindaloo at Ajanta")
  public void placeOrder() {

    response = given(). ///Invokes the Order Service REST API to create Order
            body(new CreateOrderRequest(consumerId,
                    RestaurantMother.AJANTA_ID, Collections.singletonList(
                            new CreateOrderRequest.LineItem(RestaurantMother.CHICKEN_VINDALOO_MENU_ITEM_ID,
                                                            OrderDetailsMother.CHICKEN_VINDALOO_QUANTITY)))).
            contentType("application/json").
            when().
            post(baseUrl("/orders"));
  }

  /// the verification phase
  @Then("the order should be (.*)")
  public void theOrderShouldBeInState(String desiredOrderState) {

      // TODO This doesn't make sense when the `OrderService` is live => duplicate replies

//    sagaParticipantStubManager
//            .forChannel("orderService")
//            .when(ApproveOrderCommand.class).replyWithSuccess();
//
    Integer orderId =
            this.response.
                    then().
                    statusCode(200).
                    extract().
                    path("orderId");

    assertNotNull(orderId);

    eventually(() -> {
      String state = given().
              when().
              get(baseUrl("/orders/" + orderId)).
              then().
              statusCode(200)
              .extract().
                      path("state");
      assertEquals(desiredOrderState, state);
    });

    /***
    In production, orderService should send a CreateTicket command to kitchenService through kitchenService channel.
    In component testing, sagaParticipantStubManager owns kitchenService channel and will send back a reply to orderService. 
    
    Here we let sagaParticipantStubManager if its kitchenService channel has CreateTicket command. 
    ***/ 
    sagaParticipantStubManager.verifyCommandReceived("kitchenService", CreateTicket.class);

  }

  @And("an (.*) event should be published")
  public void verifyEventPublished(String expectedEventClass) {
	///And an OrderAuthorized event should be published
	///assertDomainEventPublished(String channel, String expectedDomainEventClassName)
	///the channel that stores the outgoing event in orderService is called "net.chrisrichardson.ftgo.orderservice.domain.Order"
	///the event is OrderAuthorized
    messageTracker.assertDomainEventPublished("net.chrisrichardson.ftgo.orderservice.domain.Order",
            findEventClass(expectedEventClass, "net.chrisrichardson.ftgo.orderservice.domain", "net.chrisrichardson.ftgo.orderservice.api.events"));
  }

  private String findEventClass(String expectedEventClass, String... packages) {
    return Arrays.stream(packages).map(p -> p + "." + expectedEventClass).filter(className -> {
      try {
        Class.forName(className);
        return true;
      } catch (ClassNotFoundException e) {
        return false;
      }
    }).findFirst().orElseThrow(() -> new RuntimeException(String.format("Cannot find class %s in packages %s", expectedEventClass, String.join(",", packages))));
  }

}
