package net.chrisrichardson.ftgo.apiagateway.contract;

import net.chrisrichardson.ftgo.apiagateway.orders.OrderDestinations;
import net.chrisrichardson.ftgo.apiagateway.proxies.OrderInfo;
import net.chrisrichardson.ftgo.apiagateway.proxies.OrderNotFoundException;
import net.chrisrichardson.ftgo.apiagateway.proxies.OrderServiceProxy;
import reactor.core.publisher.Mono;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.Assert.assertEquals;

/***
https://www.baeldung.com/spring-boot-testing
The integration tests need to start up a container to execute the test cases. 
Hence, some additional setup is required for this ¨C all of this is easy in Spring Boot.
The @SpringBootTest annotation can be used when we need to bootstrap the entire container. R
The annotation works by creating the ApplicationContext that will be utilized in our tests.

/// So these ApplicationContext can be used to do bean injection I guess.

We can use the webEnvironment attribute of @SpringBootTest 
to configure our runtime environment; 
we're using WebEnvironment.MOCK here ¨C 
so that the container will operate in a mock servlet environment.


Also notice the class annotated with @RunWith(SpringRunner.class). @RunWith is
a JUnit annotation, providing a test runner that guides JUnit in running a test. Think
of it as applying a plugin to JUnit to provide custom testing behavior. In this case,
JUnit is given SpringRunner, a Spring-provided test runner that provides for the creation
of a Spring application context that the test will run against.

If you¡¯re already familiar with writing Spring tests or are maybe looking at some existing
Spring-based test classes, you may have seen a test runner named SpringJUnit4-
ClassRunner. SpringRunner is an alias for SpringJUnit4ClassRunner, and was
introduced in Spring 4.3 to remove the association with a specific version of JUnit (for
example, JUnit 4). And there¡¯s no denying that the alias is easier to read and type.
@SpringBootTest tells JUnit to bootstrap the test with Spring Boot capabilities.
For now, it¡¯s enough to think of this as the test class equivalent of calling Spring-
Application.run() in a main() method. Over the course of this book, you¡¯ll see
@SpringBootTest several times, and we¡¯ll uncover some of its power.

***/
@RunWith(SpringRunner.class)
/***
https://spring.io/guides/gs/testing-web/
The @SpringBootTest annotation tells Spring Boot to look for a main configuration class 
(one with @SpringBootApplication, 
for instance) and use that to start a Spring application context.

***/
@SpringBootTest(classes=TestConfiguration.class,
        webEnvironment= SpringBootTest.WebEnvironment.NONE)
/***
https://www.baeldung.com/spring-cloud-contract
https://aboullaite.me/a-practical-introduction-to-spring-cloud-contract/
1. Tell Spring Cloud Contract to configure WireMock with OrderService's contracts
the ids property of the @AutoConfigureStubRunner annotation specifies:

net.chrisrichardson.ftgo: the groupId of the artifact
ftgo-order-service-contracts: the contract used to generate a stub jar.

The generated stub jar is used as a server to simulate the behavior of OrderService.
)
***/
@AutoConfigureStubRunner(ids =
        {"net.chrisrichardson.ftgo:ftgo-order-service-contracts"}
)
@DirtiesContext
public class AnnotatedOrderServiceProxyIntegrationTest {

  /// Obtain the randomly assigned port that WireMock is running on. 
  @Value("${stubrunner.runningstubs.ftgo-order-service-contracts.port}")
  private int port;
  private OrderDestinations orderDestinations;
  private OrderServiceProxy orderService;

  /***
  API Gateway makes REST API calls to numerous services,
  including CosumerService, OrderService and DeliveryServce.
  Get /orders/{orderId} end point is invoked by API Gateway.
  In order to ensure API Gateway and OrderService can communicate
  without using end-to-end test, we need to write integration test.

  The interaction between API Gateway and Get /orders/{orderId}
  can be described using a set of HTTP-based contracts. 
  Each contract consists of an HTTP request and expected HTTP reply. 
  The contracts are used to test API gateway and OrderService.

  GetOrder.groovy is the contract. 
  It's used by both API Gateway integration test and
  OrderService integration test.

  1.
  API Gateway integration test (OrderServiceProxyIntegrationTest) 
  use the contracts 
  (@AutoConfigureStubRunner net.chrisrichardson.ftgo:ftgo-order-service-contracts) 
  to configure an HTTP
  stub server (Wiremock based HTTP stub server)
  that simulate the behavior of OrderService. 
  The contract's request specifies an HTTP request from the API gateway,
  and the contract's response specifies the response that the stub sends
  back to the API gateway. 

  2.
  Spring cloud contract also uses the contract to do integration testing 
  on the the provider-side orderService integration tests, 
  which test the controller using Spring Mock MVC or Rest Assured Mock MVC. 
  The contract's request specifies the HTTP request to make to the controller,
  and the contracts's response specifies the  controller expected response. 

  Check HttpBase.java under OrderService project's integration test folder.
	

  ***/
  @Before
  public void setUp() throws Exception {
    orderDestinations = new OrderDestinations();
    /// this is the stub server we're talking about
    /// It's just a fake server and you can configure it to return any
    /// responses you want
    /// note that the WireMock is already configured through the contract. 
    String orderServiceUrl = "http://localhost:" + port;
    System.out.println("orderServiceUrl=" + orderServiceUrl);
    orderDestinations.setOrderServiceUrl(orderServiceUrl);
    /// I guess this orderSevice is running inside the generated stub server
    /// 3. Create an OrderServiceProxy configured to make requests to WireMock.
    /***
    When API Gateway makes a REST call to orderService through OrderServiceProxy,
    the request is forwarded to the fake server. 
    The fake server should start at the specified port and fake the orderService 
    
    ***/
    
    orderService = new OrderServiceProxy(orderDestinations, WebClient.create());
  }

  @Test
  public void shouldVerifyExistingCustomer() {
    OrderInfo result = orderService.findOrderById("99").block();
    assertEquals("99", result.getOrderId());
    assertEquals("APPROVAL_PENDING", result.getState());
  }

  @Test(expected = OrderNotFoundException.class)
  public void shouldFailToFindMissingOrder() {
    orderService.findOrderById("555").block();
  }

}
