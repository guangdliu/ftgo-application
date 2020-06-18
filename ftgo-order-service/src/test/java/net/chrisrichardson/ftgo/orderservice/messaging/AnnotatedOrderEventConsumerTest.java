package net.chrisrichardson.ftgo.orderservice.messaging;

import net.chrisrichardson.ftgo.common.CommonJsonMapperInitializer;
import net.chrisrichardson.ftgo.orderservice.RestaurantMother;
import net.chrisrichardson.ftgo.orderservice.domain.OrderService;
import net.chrisrichardson.ftgo.restaurantservice.events.RestaurantCreated;
import net.chrisrichardson.ftgo.restaurantservice.events.RestaurantMenu;
import org.junit.Before;
import org.junit.Test;

import static io.eventuate.tram.testing.DomainEventHandlerUnitTestSupport.given;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_ID;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_RESTAURANT_NAME;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AnnotatedOrderEventConsumerTest {

  private OrderService orderService;
  private OrderEventConsumer orderEventConsumer;

  @Before
  public void setUp() throws Exception {
    orderService = mock(OrderService.class);
    orderEventConsumer = new OrderEventConsumer(orderService); /// 1 Instantiate OrderEventConsumer with mocked dependencies
  }

  @Test
  public void shouldCreateMenu() {

    CommonJsonMapperInitializer.registerMoneyModule();

    given().
            eventHandlers(orderEventConsumer.domainEventHandlers()). /// 2. Configure OrderEventConsumerDomainHandlers. 
    when().
            aggregate("net.chrisrichardson.ftgo.restaurantservice.domain.Restaurant", AJANTA_ID).
            publishes(new RestaurantCreated(AJANTA_RESTAURANT_NAME, RestaurantMother.AJANTA_RESTAURANT_MENU)). /// 3. Publish a RestaurantCreated event
    then().
       verify(() -> { /// 4 Verfiy that OrderEventConsumer invoked OrderService.createMenu()
         verify(orderService).createMenu(AJANTA_ID, AJANTA_RESTAURANT_NAME, new RestaurantMenu(RestaurantMother.AJANTA_RESTAURANT_MENU_ITEMS));
       })
    ;

  }

}