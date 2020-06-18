package net.chrisrichardson.ftgo.apiagateway.proxies;

import net.chrisrichardson.ftgo.apiagateway.ApiGatewayIntegrationTestConfiguration;
import net.chrisrichardson.ftgo.apiagateway.orders.OrderDestinations;

import org.junit.runner.RunWith;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AnnotatedOrderServiceProxy {


  private OrderDestinations orderDestinations;

  private WebClient client;

  /// orderDestinations.orderServiceUrl is like: http://localhost:10001
/***
Example at ApiGatewayIntegrationTest: 
  
@RunWith(SpringRunner.class)
@SpringBootTest(classes = ApiGatewayIntegrationTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"order.destinations.orderServiceUrl=http://localhost:8082",
                "order.destinations.orderHistoryServiceUrl=http://localhost:8083",
                "consumer.destinations.consumerServiceUrl=http://localhost:9999"
})

OrderDestination is annotated with @ConfigurationProperties(prefix = "order.destinations")

I guess, Spring checks config info from different sources,
if it finds a property like this  https://www.baeldung.com/spring-enable-config-properties,
it'll create an object of OrderDestinations and
OrderDestinations.orderServiceUrl = http://localhost:8082.
This object is injected to OrderServiceProxy. 

OrderDestinations.orderServiceUrl is the internal service endpoint.
OrderServiceProxy uses WebClient to invoke this internal service. 
OrderServiceProxy is then injected to OrderHandlers.

OrderServiceProxy is then injected to OrderHandlers

OrderHandlers is used in OrderConfiguration for customized request handling.

OrderConfiguration is annotated with @Configuration. I guess it'll be scanned when the server starts. 
Server crerates servral beans based on the OrderConfiguration. 



  ***/
  public AnnotatedOrderServiceProxy(OrderDestinations orderDestinations, WebClient client) {
    this.orderDestinations = orderDestinations;
    this.client = client;
  }

  public Mono<OrderInfo> findOrderById(String orderId) {
	/// I guess client sends a request to the stub server
	/// if the request is the same as the one specified in GetOrder.groovy,
	/// then stub server sends back the response specified in GetOrder.groovy
	/// which is an OrderInfo; otherwise, the stub server sends back other invalid
	/// responses.
    Mono<ClientResponse> response = client
            .get()
            .uri(orderDestinations.getOrderServiceUrl() + "/orders/{orderId}", orderId)
            .exchange();
    return response.flatMap(resp -> {
      switch (resp.statusCode()) {
        case OK:
          return resp.bodyToMono(OrderInfo.class);
        case NOT_FOUND:
          return Mono.error(new OrderNotFoundException());
        default:
          return Mono.error(new RuntimeException("Unknown" + resp.statusCode()));
      }
    });
  }


}
