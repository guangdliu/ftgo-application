package net.chrisrichardson.ftgo.orderservice.domain;

import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.common.Command;
import io.eventuate.tram.commands.common.CommandMessageHeaders;
import io.eventuate.tram.commands.common.CommandReplyOutcome;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.commands.common.Success;
import io.eventuate.tram.commands.producer.CommandProducer;
import io.eventuate.tram.events.aggregates.ResultWithDomainEvents;
import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.consumer.MessageConsumer;
import io.eventuate.tram.messaging.producer.MessageBuilder;
import io.eventuate.tram.sagas.common.SagaLockManager;
import io.eventuate.tram.sagas.orchestration.Saga;
import io.eventuate.tram.sagas.orchestration.SagaCommandProducer;
import io.eventuate.tram.sagas.orchestration.SagaInstanceRepository;
import io.eventuate.tram.sagas.orchestration.SagaManager;
import io.eventuate.tram.sagas.participant.SagaCommandDispatcher;
import io.eventuate.tram.sagas.participant.SagaCommandDispatcherFactory;
import io.micrometer.core.instrument.MeterRegistry;
import net.chrisrichardson.ftgo.common.UnsupportedStateTransitionException;
import net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb.Maps;
import net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb.Order;
import net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb.SourceEvent;
import net.chrisrichardson.ftgo.kitchenservice.api.CreateTicketReply;
import net.chrisrichardson.ftgo.kitchenservice.api.KitchenServiceChannels;
import net.chrisrichardson.ftgo.kitchenservice.messagehandlers.KitchenServiceCommandHandler;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderDetails;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderDomainEvent;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderLineItem;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderRejected;
import net.chrisrichardson.ftgo.orderservice.grpc.OrderServiceGrpc;
import net.chrisrichardson.ftgo.orderservice.messaging.OrderServiceMessagingConfiguration;
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.AccountingServiceProxy;
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.ConsumerServiceProxy;
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.KitchenServiceProxy;
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.OrderServiceProxy;
import net.chrisrichardson.ftgo.orderservice.sagas.cancelorder.CancelOrderSagaData;
import net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSagaState;
import net.chrisrichardson.ftgo.orderservice.sagas.reviseorder.ReviseOrderSagaData;
import net.chrisrichardson.ftgo.orderservice.web.MenuItemIdAndQuantity;
import net.chrisrichardson.ftgo.restaurantservice.events.MenuItem;
import net.chrisrichardson.ftgo.restaurantservice.events.RestaurantCreated;
import net.chrisrichardson.ftgo.restaurantservice.events.RestaurantMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static net.chrisrichardson.ftgo.orderservice.api.events.OrderState.REJECTED;

/***

Consumers ues the FTGO website or mobile application to place orders
at local restaurants. 
FTGO coordinates a network of couries who 
deliver the orders. 
It's also repsponsible for paying couries and restaurants. 
(Think about Insta cart as example). 
Restaurants use the FTGO website to edit their munus and manage orders. 
The application uses various web services, including
Stripe for payments, Twiilio for messaging and Amazon Simple Email 
Service for email.
***/
@Transactional
public class AnnotatedOrderService {

  private Logger logger = LoggerFactory.getLogger(getClass());

  private OrderRepository orderRepository;

  private RestaurantRepository restaurantRepository;

  private SagaManager<CreateOrderSagaState> createOrderSagaManager;

  private SagaManager<CancelOrderSagaData> cancelOrderSagaManager;

  private SagaManager<ReviseOrderSagaData> reviseOrderSagaManager;

  private OrderDomainEventPublisher orderAggregateEventPublisher;

  private Optional<MeterRegistry> meterRegistry; 
  /// The Micrometer Metrics library API for managing application-specific meters

  /***
  Fig 3.8
  The client (OrderService) sends a command message (CreateTicket)  ,
  which specifies the operation to perform and params to 
  a mysql db table (called message from template.sql) owned by OrderService. 
  The table acts a temporary message queue. The MessageReplay is component of 
  Eventuate that reads the message table and publishes the message to a message 
  broker (Figure 3.13). Message broker reads the message header which includes the
  url and channel info. Message broker publishes the message to
  a point-to-point
  messaging channel owned by a service (KitchenServiceChannels.kitchenServiceChannel).
  Note that the kichenService subscribes this channel so it'll be notified when 
  the message arrives. 
  The service (KitchenServiceCommandHandler) process the requests 
  and sends a reply message (CreateTicketReply) which contains the outcome,
  to a point-to-point channel 
  (net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga-reply in CreateTicket.groovy)
  owned by the client (OrderSerivice)
  
  Saga reply channel is named as sagaType + "-reply"
  From above, it looks like this saga reply channel belongs to CreateOrderSaga
  net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga is sagaType.
  
  When does CreateOrderSaga creates its reply channel?
  CreateOrderSaga belongs to createOrderSagaManager.
  
  If you check createOrderSagaManager.processActions,
  it has 
  
  String lastRequestId = sagaCommandProducer.sendCommands(this.getSagaType(), sagaId, actions.getCommands(), this.makeSagaReplyChannel());

 createOrderSagaManager.makeSagaReplyChannel() is the reply message channel that's owned by OrderService. 
 
 Eventually, SagaManager.SagaCommandProducer sents this message to KitchenService:
 
   public static Message makeMessage(String channel, String resource, Command command, String replyTo, Map<String, String> headers) {
    MessageBuilder builder = MessageBuilder.withPayload(JSonMapper.toJson(command))
            .withExtraHeaders("", headers) // TODO should these be prefixed??!
            .withHeader(CommandMessageHeaders.DESTINATION, channel) 
           /// destination Channel is KitchenServiceChannels.kitchenServiceChannel defined in KitchenServiceProxy
            .withHeader(CommandMessageHeaders.COMMAND_TYPE, command.getClass().getName())
           /// replyTo is the reply channel owned by OrderService.createOrderSagaManager
            .withHeader(CommandMessageHeaders.REPLY_TO, replyTo);
 
 also, 
 
 public void createOrderSagaManager.subscribeToReplyChannel() {
    messageConsumer.subscribe(saga.getSagaType() + "-consumer", singleton(makeSagaReplyChannel()),
            this::handleMessage);
  }
  
  You can see that createOrderSag.messageConsumer subscribes this channel, if KitcheService sends back
  its reply, OrderService's saga will handle it based on sagDefinition's state machine. 
  
  One thing to note is that each SagaManger (createOrderSagaManager, cancelOrderSagaManager and
  reviseOrderSagaManager) has their own reply channel, their own messageProducer and messageConsumer which
  subscribes the reply channel. 
  
  From above, you can see that the command message is sent to 
   KitchenServiceChannels.kitchenServiceChannel
   
  Eventuate Framework has a class called 
  
   SagaCommandDispatcher extends CommandDispacher
   
   If you AnnotatedCommandDispatcher.messageHandler, 
   you'll find it invoke 
   KitcheServiceCommandHanlder.commandHanlder to get the result from KichenService,
   then 
   it sends the result back to the 
   
     (net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga-reply in CreateTicket.groovy)

   

  ***/
  public AnnotatedOrderService(OrderRepository orderRepository, DomainEventPublisher eventPublisher, RestaurantRepository restaurantRepository, SagaManager<CreateOrderSagaState> createOrderSagaManager, SagaManager<CancelOrderSagaData> cancelOrderSagaManager, SagaManager<ReviseOrderSagaData> reviseOrderSagaManager, OrderDomainEventPublisher orderAggregateEventPublisher, Optional<MeterRegistry> meterRegistry) {
    this.orderRepository = orderRepository;
    this.restaurantRepository = restaurantRepository;
    this.createOrderSagaManager = createOrderSagaManager;
    this.cancelOrderSagaManager = cancelOrderSagaManager;
    this.reviseOrderSagaManager = reviseOrderSagaManager;
    this.orderAggregateEventPublisher = orderAggregateEventPublisher;
    this.meterRegistry = meterRegistry;
  }

  /***
  OrderSerice.createOrder is invoked by
  a. OrderServiceServer.(OrderServiceImpl extends OrderServiceGrpc.OrderServiceImplBase).createOrder
  b. OderController.create
  
  a is grpc and b is rest
  ***/
  public Order createOrder(long consumerId, long restaurantId,
                           List<MenuItemIdAndQuantity> lineItems) {
    Restaurant restaurant = restaurantRepository.findById(restaurantId)
            .orElseThrow(() -> new RestaurantNotFoundException(restaurantId));

    List<OrderLineItem> orderLineItems = makeOrderLineItems(lineItems, restaurant);

    /***
    It creates an OrderCreatedEvent.
    
    OrderCreatedEvent eventually is processed by
    OrderHistoryEventHandlers.handleOrderCreated where it saves an order to db. 
        boolean result = orderHistoryDao.addOrder(makeOrder(dee.getAggregateId(), dee.getEvent()), makeSourceEvent(dee));
        orderHistoryDao.addOrder calls
	        orderHistoryDao.idempotentUpdate(UpdateItemSpec spec, Optional<SourceEvent> eventSource)
	    to save order.

	2. Create an order
    ***/
    ResultWithDomainEvents<Order, OrderDomainEvent> orderAndEvents =
            Order.createOrder(consumerId, restaurant, orderLineItems);

    Order order = orderAndEvents.result;
    /// 3. persists the Order in the database
    orderRepository.save(order);

    /// 4. Publish domain events
    /***
    From Chapter 3,
    the domain events are emitted by an aggregate when it's created,
    updated, or deleted. A service publishes a domain event using the 
    DomainEventPublisher interface. A service uses the *EventConsumer(e.g. OrderEventConsumer)
    consumes the domain event. 
    
    Events aren't the only high-level messaging pattern supported by Eventuate Tram. 
    It also supports command/reply-based messaing. 
    A client can send a command message to a service using the CommandProducer interface.
    For example, SagaManagerImpl.processActions, it has
    
            String lastRequestId = sagaCommandProducer.sendCommands(this.getSagaType(), sagaId, actions.getCommands(), this.makeSagaReplyChannel());
            
    The command message is consumed by CommandDispatcher class (e.g. KitchenServiceCommandHandler and KitchenServiceMessageHandlersConfiguration)
    
	  @Bean
	  public SagaCommandDispatcher KitchenServiceMessageHandlersConfiguration.kitchenServiceSagaCommandDispatcher(KitchenServiceCommandHandler kitchenServiceCommandHandler, SagaCommandDispatcherFactory sagaCommandDispatcherFactory) {
	    return sagaCommandDispatcherFactory.make("kitchenServiceCommands", kitchenServiceCommandHandler.commandHandlers());
	  }

    ***/
    
    /***
    Chapter 5
    The Eventuate framework uses a db called OUTBOX as part of ACID transaction
    After the transaction commits, the events that were inserted into the OUTBOX
    table are then published to the message broker. It uses Eventuate MessageProducer
    interface to publish those messages transactionally.
    
    AbstractAggreateDomainEventPublisher
    
    Basically, OrderService has a db table called (I don't know, maybe message). 
    orderAggregateEventPublisher publishes this event to that db table. 
    A message broker (e.g. Eventuate framework) reads that db table and broadcast the 
    message to the framework. Other services who subscribes the topic of the framework
    can be notified then. 
    
    ***/
    
    orderAggregateEventPublisher.publish(order, orderAndEvents.events);

    OrderDetails orderDetails = new OrderDetails(consumerId, restaurantId, orderLineItems, order.getOrderTotal());

    /***
    5. create a CreateOrderSaga
    CreateOrderSagaState contains the ID of the newly saved Order and the OrderDetails. 
    The createOrderSagaManager instantiates the saga orchestrator (SagaInstance which is created by createOrderSagaManager.create),
    which causes it (SagaInstance or sagaCommandProducer) 
    to send a command message to the first saga participant (createOrderSagaManager.processActions), 
    and persists the saga orchestrator (sagaInstanceRepository.save(sagaInstance) in createOrderSagaManager.create ) in the database
    
    CreateOrderSagaState and  CreateOrderSaga are two different classes. 
    CreateOrderSaga is a singleton class that defines the saga's state machine. 
    CreateOrderSaga is initialized in this path:
    	OrderServiceMain
    	OrderServiceMessagingConfiguration or OrderWebConfiguration
    	OrderServiceWithRepositoriesConfiguration
    	OrderServiceConfiguration.createOrderSaga
    
    CreateOrderSaga invokes the CreateOrderSagaState to create command messages, 
    and sends them to participants using channels specified by the saga participant proxy classes, such as KitchenServiceProxy. 
    These proxy classes are used by CreateOrderSaga contructor is 
    	CreateOrderSaga(OrderServiceProxy orderService, 
    					ConsumerServiceProxy consumerService, 
    					KitchenServiceProxy kitchenService,
                        AccountingServiceProxy accountingService)
                        
    CreateOrderSaga.sagaDefinition is what the state machine, 
    how states tranfer from one state and another state, 
    what messages are sent,
    how the messages are hanldes during states change. 
    
    CreateOrderSagaState: a saga's persistent state which creates command messages. 
    
    Saga participant proxy classes, such as KitchenServiceProxy, each proxy class defines a saga participant's messageing API, which
    consists of the command channel, the command message types and the reply types. 
    
    createOrderSagaManager is also singleton class. Its constructor takes CreateOrderSaga as param (OrderServiceConfiguration).
    Data class is CreateOrderSagaState for createOrderSagaManager and CreateOrderSaga. 
    
    createOrderSagaManager.create invokes
    	getStateDefinition().start(sagaData)
    which executes the first step of the sagaDefintition.
    
    sagaDefinition is initialized in 
    CreateOrderSaga constructor
    
		      public CreateOrderSaga(OrderServiceProxy orderService, ConsumerServiceProxy consumerService, KitchenServiceProxy kitchenService,
			                         AccountingServiceProxy accountingService) {
			    this.sagaDefinition =
			    		...
			            .step()
			              .invokeParticipant(kitchenService.create, CreateOrderSagaState::makeCreateTicketCommand)
			              .onReply(CreateTicketReply.class, CreateOrderSagaState::handleCreateTicketReply)
			              .withCompensation(kitchenService.cancel, CreateOrderSagaState::makeCancelCreateTicketCommand)
			            ...
			            .build();
			  }
    
    SageDefinition is a state machine. 
    	invokeParticipant(kitchenService.create, CreateOrderSagaState::makeCreateTicketCommand)
    Make CreateOrderSagaState to create a CreateTicketCommand message. 
    Send the message to the channel (KitchenServiceChannels.kitchenServiceChannel) specified by KitchenServiceProxy.create.
    KitchenServiceCommandHandler is reading messages in KitchenServiceChannels.kitchenServiceChannel.
    From KitchenServiceCommandHandler.commandHandlers, you can see this CreateTicketCommand message is handled by 
    KitchenServiceCommandHandler.createTicket.
    KitchenServiceCommandHandler.createTicket invokes
    kitchenService.createTicket(restaurantId, ticketId, ticketDetails). 
    If  kitchenService.createTicket is successful,
    KitchenServiceCommandHandler.createTicket creates a CreateTicketReply message.
    and sends back to a saga reply channel owned by OrderService
    CreateOrderSaga defines CreateOrderSagaState.handleCreateTicketReply to handle CreateTicketReply message.
    
    CreateOrderSaga also defines the next move (e.g.  CreateOrderSagaState::makeAuthorizeCommand). 
    
    If you check CreateOrderSaga, its state machine CreateOrderSaga.sagaDefinition has
     	.onReply(CreateTicketReply.class, CreateOrderSagaState::handleCreateTicketReply)
    	.step()
        .invokeParticipant(accountingService.authorize, CreateOrderSagaState::makeAuthorizeCommand)
    
    You can see that CreateOrderSagaState will call handleCreateTicketReply to set CreateOrderSagaState.ticketId.
    then go forward by CreateOrderSagaState::makeAuthorizeCommand. 
    
    ***/
    CreateOrderSagaState data = new CreateOrderSagaState(order.getId(), orderDetails);
    
    /***
    As described in Chapter 3, the client (CreateOrderSaga) sends a command message
    which specifies the operation (CreateTicket) to perform and parameters, to a 
    point-to-point messaging channel (KitchenServiceChannels.kitchenServiceChannel) owned by
    a service(KitchenService). The service processes the requests and sends a reply message,
    which contains the outcome, to a point-to-point channel owned by the client(
    net.chrisrichardson.ftgo.orderservice.api.events.OrderCreatedEvent in OrderCreateEvent.groovy)
    
    ***/
    
    createOrderSagaManager.create(data, Order.class, order.getId());

    meterRegistry.ifPresent(mr -> mr.counter("placed_orders").increment());

    return order;
    
    /***
    We have a state machine defined in CreateOrderSaga.SageDefinition to describe a state machine 
    when mutliple services are going to be called. 
    This state machine describes what messages are created by CreateOrderSagaState 
    and who handles the message (e.g. KitchenServiceProxy.create).
    A proxy class handles the message by putting the message in a channel, e.g. KitchenServiceChannels.kitchenServiceChannel.
    A *CommandHandler class (e.g. KitchenServiceCommandHandler) reads the message from the channel, processes it by 
    invoking services (e.g. KitchenService), sends *Reply messages. The *Reply message are handled by CreateOrderSagaState, 
    and then move forward with state transfering in CreateOrderSagaState.
    
    At runtime, 
    1. OrderService creates the CreateOrderSagaState.
    2. OrderService creates a instance of sagaInstance by invoking createOrderSagaManager.create
    3. The createOrderSagaManager creates a list of actions by compiling createOrderSagaManager.CreateOrderSaga.sagaDefinition 
    	by invoking getStateDefinition().start(sagaData) in createOrderSagaManager.create.
    4. The CreateOrderSagaState executes these actions one by one in a while loop. 
    5. Each action generate a command message (SagaManagerImpl.processAction).
    5.1 The createOrderSagaManager sends command message to the saga participant (e.g. the kitchen service) via a message channel.
        createOrderSagaManager uses info in the createOrderSagaManager.saga.sagaDefinition 
    	to send the message to a *Proxy's CommandEndpoint which contains the message channel.  
    	e.g. kitchenServiceProxy.create (e.g.  kitchenServiceProxy).
    	
    	A CommandEndpoint has a message type, message channel  and reply message type.
    	Message channel, e.g. KitchenServiceChannels.kitchenServiceChannel.
    5.2 createOrderSagaManager saves the sagaInstance to db
    	sagaInstanceRepository.update(sagaInstance) in processOrder.
    5.3 KitchenServiceCommandHandler recieves the message and invoke KitchenServiceCommandHandler.createTicket. 
        KitchenServiceCommandHandler.createTicket invokes  kitchenService.createTicket(restaurantId, ticketId, ticketDetails)
        to create a ticket, then sends a CreateTicketReply. This reply message is handled by SagaReplyRequestEventHanlder 
        by sending it to  CreateOrderSaga's reply channel.
        
    5.4 when a Reply messange is sent back to CreateOrderSaga's reply channel from a consumer service, e.g. from kitchenService 
    	(I don't how the Reply is sent back to SagaManager, but I guess it's still from proxy service because proxy service know the channel,
    	Also, SagaManager knows the proxies from SagaManager.saga). SagaManager is going to handle this in processActions:
    	  actions = getStateDefinition().handleReply(actions.getUpdatedState().get(), actions.getUpdatedSagaData().get(), MessageBuilder
                .withPayload("{}")
                .withHeader(ReplyMessageHeaders.REPLY_OUTCOME, CommandReplyOutcome.SUCCESS.name())
                .withHeader(ReplyMessageHeaders.REPLY_TYPE, Success.class.getName())
                .build());
      }
    
    ***/
  }


  private List<OrderLineItem> makeOrderLineItems(List<MenuItemIdAndQuantity> lineItems, Restaurant restaurant) {
    return lineItems.stream().map(li -> {
      MenuItem om = restaurant.findMenuItem(li.getMenuItemId()).orElseThrow(() -> new InvalidMenuItemIdException(li.getMenuItemId()));
      return new OrderLineItem(li.getMenuItemId(), om.getName(), om.getPrice(), li.getQuantity());
    }).collect(toList());
  }


  public Optional<Order> confirmChangeLineItemQuantity(Long orderId, OrderRevision orderRevision) {
    return orderRepository.findById(orderId).map(order -> {
      List<OrderDomainEvent> events = order.confirmRevision(orderRevision);
      orderAggregateEventPublisher.publish(order, events);
      return order;
    });
  }

  public void noteReversingAuthorization(Long orderId) {
    throw new UnsupportedOperationException();
  }

  public Order cancel(Long orderId) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    CancelOrderSagaData sagaData = new CancelOrderSagaData(order.getConsumerId(), orderId, order.getOrderTotal());
    cancelOrderSagaManager.create(sagaData);
    return order;
  }

  private Order updateOrder(long orderId, Function<Order, List<OrderDomainEvent>> updater) {
    return orderRepository.findById(orderId).map(order -> {
      orderAggregateEventPublisher.publish(order, updater.apply(order));
      return order;
    }).orElseThrow(() -> new OrderNotFoundException(orderId));
  }

  public void approveOrder(long orderId) {
    updateOrder(orderId, Order::noteApproved);
    meterRegistry.ifPresent(mr -> mr.counter("approved_orders").increment());
  }

  public void rejectOrder(long orderId) {
    updateOrder(orderId, Order::noteRejected);
	/***
	
	OrderCommandHandlers fetches CommandMessage from a channel called "orderService"
	OrderCommandHandlers.rejectOrder(CommandMessage<RejectOrderCommand> cm)
	OrderService.rejectOrder(long orderId) is called at
	
	
	
	
 
 
 
	  Order updateOrder(long orderId, Function<Order, List<OrderDomainEvent>> updater)
	  
	  updateOrder.updater is a Function. Its input is an Order and output a list of Events
	  Order::noteRejected is a function (instance method).
	  instance method default first param is instance itself.
	  Order::noteRejected therefore is a Function<Order, List<OrderDomainEvent>>
	  but it doesn't delete the order from db, right?
	  yeah, kinda. Order is not deleted from db at Order::noteRejected for sure, 
	  but we mark Order status as Rejected. 
	  At OrderHistoryEventHandler.handleOrderRejected,
	  it calls 
	  OrderHistoryDaoDynamoDb.updateOrderState.
	  It won't delete the order, but mark the order status as Rejected at db. 
	  
	  It marks Order.state = REJECTED and generates a single elem list 
	  
	  public List<OrderDomainEvent> Order.noteRejected() {
	    switch (state) {
	      case APPROVAL_PENDING:
	        this.state = REJECTED;
	        return singletonList(new OrderRejected());
	
	      default:
	        throw new UnsupportedStateTransitionException(state);
	    }
	  }  
	***/
    meterRegistry.ifPresent(mr -> mr.counter("rejected_orders").increment());
  }

  public void beginCancel(long orderId) {
    updateOrder(orderId, Order::cancel);
  }

  public void undoCancel(long orderId) {
    updateOrder(orderId, Order::undoPendingCancel);
  }

  public void confirmCancelled(long orderId) {
    updateOrder(orderId, Order::noteCancelled);
  }

  public Order reviseOrder(long orderId, OrderRevision orderRevision) {
    Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    ReviseOrderSagaData sagaData = new ReviseOrderSagaData(order.getConsumerId(), orderId, null, orderRevision);
    reviseOrderSagaManager.create(sagaData);
    return order;
  }

  public Optional<RevisedOrder> beginReviseOrder(long orderId, OrderRevision revision) {
    return orderRepository.findById(orderId).map(order -> {
      ResultWithDomainEvents<LineItemQuantityChange, OrderDomainEvent> result = order.revise(revision);
      orderAggregateEventPublisher.publish(order, result.events);
      return new RevisedOrder(order, result.result);
    });
  }

  public void undoPendingRevision(long orderId) {
    updateOrder(orderId, Order::rejectRevision);
  }

  public void confirmRevision(long orderId, OrderRevision revision) {
    updateOrder(orderId, order -> order.confirmRevision(revision));
  }

  /***
  OrderService.createMenu is invoked in OrderEventConsumer.createMenu(DomainEventEnvelope<RestaurantCreated> de).
  
  OrderEventConsumer subscribes RestaurantEventChannel and is notified after RestaurantEventChannel has new messages. 
  
  
  We have three types channel so far:
  
  *EventChannel
  *CommandChannel
  *ReplyChannel
  ***/
  @Transactional(propagation = Propagation.MANDATORY)
  public void createMenu(long id, String name, RestaurantMenu menu) {
    Restaurant restaurant = new Restaurant(id, name, menu.getMenuItems());
    restaurantRepository.save(restaurant);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reviseMenu(long id, RestaurantMenu revisedMenu) {
    restaurantRepository.findById(id).map(restaurant -> {
      List<OrderDomainEvent> events = restaurant.reviseMenu(revisedMenu);
      return restaurant;
    }).orElseThrow(RuntimeException::new);
  }

}
