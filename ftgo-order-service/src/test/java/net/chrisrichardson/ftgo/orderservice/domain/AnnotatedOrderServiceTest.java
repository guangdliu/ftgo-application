package net.chrisrichardson.ftgo.orderservice.domain;

import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.sagas.orchestration.SagaManager;
import net.chrisrichardson.ftgo.orderservice.RestaurantMother;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderCreatedEvent;
import net.chrisrichardson.ftgo.orderservice.sagas.cancelorder.CancelOrderSagaData;
import net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSagaState;
import net.chrisrichardson.ftgo.orderservice.sagas.reviseorder.ReviseOrderSagaData;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;

import static net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.CHICKEN_VINDALOO_MENU_ITEMS_AND_QUANTITIES;
import static net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.CHICKEN_VINDALOO_ORDER_DETAILS;
import static net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.CONSUMER_ID;
import static net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.ORDER_ID;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_ID;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_RESTAURANT;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AnnotatedOrderServiceTest {

  private OrderService orderService;
  private OrderRepository orderRepository;
  private DomainEventPublisher eventPublisher;
  private RestaurantRepository restaurantRepository;
  private SagaManager<CreateOrderSagaState> createOrderSagaManager;
  private SagaManager<CancelOrderSagaData> cancelOrderSagaManager;
  private SagaManager<ReviseOrderSagaData> reviseOrderSagaManager;
  private OrderDomainEventPublisher orderAggregateEventPublisher;

  @Before
  public void setup() {
    orderRepository = mock(OrderRepository.class); /// 1. Create Mockito mocks for OrderService's dependencies.
    eventPublisher = mock(DomainEventPublisher.class);
    restaurantRepository = mock(RestaurantRepository.class);
    createOrderSagaManager = mock(SagaManager.class);
    cancelOrderSagaManager = mock(SagaManager.class);
    reviseOrderSagaManager = mock(SagaManager.class);

    // Mock DomainEventPublisher AND use the real OrderDomainEventPublisher

    orderAggregateEventPublisher = mock(OrderDomainEventPublisher.class);

    orderService = new OrderService(orderRepository, eventPublisher, restaurantRepository, /// 2. Create orderService injected with mock dependencies.
            createOrderSagaManager, cancelOrderSagaManager, reviseOrderSagaManager, orderAggregateEventPublisher, Optional.empty());
  }


  @Test
  public void shouldCreateOrder() {
    when(restaurantRepository.findById(AJANTA_ID)).thenReturn(Optional.of(AJANTA_RESTAURANT)); /// 3. Configure RestaurantRepository.findByById() to return the Ajanta restaurant
    when(orderRepository.save(any(Order.class))).then(invocation -> { /// 4. Configure OrderRepsoitory.save() to set Order's ID
      Order order = (Order) invocation.getArguments()[0];
      order.setId(ORDER_ID);
      return order;
    });

    Order order = orderService.createOrder(CONSUMER_ID, AJANTA_ID, CHICKEN_VINDALOO_MENU_ITEMS_AND_QUANTITIES); /// 5. Invoke OrderService.create()

    verify(orderRepository).save(same(order)); /// 6.Verify that OrderService saved the newly created Order in the database.

    verify(orderAggregateEventPublisher).publish(order,  /// 7 Verfiy that OrderService published an OrderCreatedEvent
            Collections.singletonList(new OrderCreatedEvent(CHICKEN_VINDALOO_ORDER_DETAILS, RestaurantMother.AJANTA_RESTAURANT_NAME)));

    verify(createOrderSagaManager).create(new CreateOrderSagaState(ORDER_ID, CHICKEN_VINDALOO_ORDER_DETAILS), Order.class, ORDER_ID); /// 8 Verfiy that OrderService created a CreateOrderSaga
  }

  // TODO write tests for other methods

}