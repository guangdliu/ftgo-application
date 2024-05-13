package net.chrisrichardson.ftgo.apiagateway.orders;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.validation.constraints.NotNull;

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
@ConfigurationProperties(prefix = "order.destinations")
public class AnnotatedOrderDestinations {

  @NotNull
  private String orderServiceUrl;

  @NotNull
  private String orderHistoryServiceUrl;

  public String getOrderHistoryServiceUrl() {
    return orderHistoryServiceUrl;
  }

  public void setOrderHistoryServiceUrl(String orderHistoryServiceUrl) {
    this.orderHistoryServiceUrl = orderHistoryServiceUrl;
  }


  public String getOrderServiceUrl() {
    return orderServiceUrl;
  }

  public void setOrderServiceUrl(String orderServiceUrl) {
    this.orderServiceUrl = orderServiceUrl;
  }
}
