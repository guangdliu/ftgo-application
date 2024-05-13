package io.eventuate.tram.sagas.orchestration;

import io.eventuate.tram.commands.common.*;
import io.eventuate.tram.commands.producer.CommandProducer;
import io.eventuate.tram.messaging.common.ChannelMapping;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.consumer.MessageConsumer;
import io.eventuate.tram.messaging.producer.MessageBuilder;
import io.eventuate.tram.sagas.common.*;
import io.eventuate.tram.sagas.orchestration.SagaActions;
import io.eventuate.tram.sagas.simpledsl.SagaExecutionState;
import io.eventuate.tram.sagas.simpledsl.SagaExecutionStateJsonSerde;
import io.eventuate.tram.sagas.simpledsl.SagaStep;
import net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSagaState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

import static java.util.Collections.singleton;

@Component
public class AnnotatedSagaManagerImpl<Data>
        implements SagaManager<Data> {

  private Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  private SagaInstanceRepository sagaInstanceRepository;

  @Autowired
  private CommandProducer commandProducer;

  @Autowired
  private MessageConsumer messageConsumer;


  private Saga<Data> saga;

  public SagaManagerImpl(Saga<Data> saga) {
    this.saga = saga;
  }


  public SagaManagerImpl(Saga<Data> saga, SagaInstanceRepository sagaInstanceRepository, CommandProducer
          commandProducer, MessageConsumer messageConsumer,
                         SagaLockManager sagaLockManager, SagaCommandProducer sagaCommandProducer) {
    this.saga = saga;
    this.sagaInstanceRepository = sagaInstanceRepository;
    this.commandProducer = commandProducer;
    this.messageConsumer = messageConsumer;
    this.sagaLockManager = sagaLockManager;
    this.sagaCommandProducer = sagaCommandProducer;
  }


  @Autowired
  private SagaLockManager sagaLockManager;


  public void setSagaCommandProducer(SagaCommandProducer sagaCommandProducer) {
    this.sagaCommandProducer = sagaCommandProducer;
  }

  @Autowired
  private SagaCommandProducer sagaCommandProducer;


  public void setSagaInstanceRepository(SagaInstanceRepository sagaInstanceRepository) {
    this.sagaInstanceRepository = sagaInstanceRepository;
  }

  public void setCommandProducer(CommandProducer commandProducer) {
    this.commandProducer = commandProducer;
  }

  public void setMessageConsumer(MessageConsumer messageConsumer) {
    this.messageConsumer = messageConsumer;
  }


  public void setSagaLockManager(SagaLockManager sagaLockManager) {
    this.sagaLockManager = sagaLockManager;
  }

  @Override
  public SagaInstance create(Data sagaData) {
    return create(sagaData, Optional.empty());
  }

  /***
  data is new CreateOrderSagaState(order.getId(), orderDetails);
  targetClass is Order.class
  targetId is order.getId()
  ***/
  @Override
  public SagaInstance create(Data data, Class targetClass, Object targetId) {
    return create(data, Optional.of(new LockTarget(targetClass, targetId).getTarget()));
  }

  /***
  sagaData is new CreateOrderSagaState(order.getId(), orderDetails);
  resource is Order/orderId
  ***/
  @Override
  public SagaInstance create(Data sagaData, Optional<String> resource) {


	///I thin getSagaType is net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga
    SagaInstance sagaInstance = new SagaInstance(getSagaType(),
            null, /// is for SagaInstance.id
            "????", /// SagaInstance.stateName
            null, /// SagaInstance.lastRequestId
            SagaDataSerde.serializeSagaData(sagaData), /// SagaInstance.serializedSagaData
            new HashSet<>()); ///SagaInstance.destinationsAndResources

    /***
     every time we create an order, we create a SagaInstance.
     This instance is saved in db sagaInstanceRepository
     
     sagaInstanceRepository.save
     
         sagaInstance.setId(idGenerator.genId().asString());

    ***/
    sagaInstanceRepository.save(sagaInstance);

    
    String sagaId = sagaInstance.getId();

    ///Saga is CreateOrderSaga which is shared accross all CreateOrder events.
    saga.onStarting(sagaId, sagaData);

    ///resource is  Order/orderId
    resource.ifPresent(r -> Assert.isTrue(sagaLockManager.claimLock(getSagaType(), sagaId, r), "Cannot claim lock for resource"));

    ///SimpleSagaDefinition<Data>.start
    SagaActions<Data> actions = getStateDefinition().start(sagaData);

    actions.getLocalException().ifPresent(e -> {
      throw e;
    });

    processActions(sagaId, sagaInstance, sagaData, actions);

    return sagaInstance;
  }


  private void performEndStateActions(String sagaId, SagaInstance sagaInstance, boolean compensating, Data sagaData) {
    for (DestinationAndResource dr : sagaInstance.getDestinationsAndResources()) {
      Map<String, String> headers = new HashMap<>();
      headers.put(SagaCommandHeaders.SAGA_ID, sagaId);
      headers.put(SagaCommandHeaders.SAGA_TYPE, getSagaType()); // FTGO SagaCommandHandler failed without this but the OrdersAndCustomersIntegrationTest was fine?!?
      commandProducer.send(dr.getDestination(), dr.getResource(), new SagaUnlockCommand(), makeSagaReplyChannel(), headers);
    }

    if (compensating)
      saga.onSagaRolledBack(sagaId, sagaData);
    else
      saga.onSagaCompletedSuccessfully(sagaId, sagaData);

  }

  private SagaDefinition<Data> getStateDefinition() {
    SagaDefinition<Data> sm = saga.getSagaDefinition();
    Assert.notNull(sm, "state machine cannot be null");
    return sm;
  }

  private String getSagaType() {
    return saga.getSagaType();
  }


  @PostConstruct
  public void subscribeToReplyChannel() {
    messageConsumer.subscribe(saga.getSagaType() + "-consumer", singleton(makeSagaReplyChannel()),
            this::handleMessage);
  }

  private String makeSagaReplyChannel() {
    return getSagaType() + "-reply";
  }


  public void handleMessage(Message message) {
    logger.debug("handle message invoked {}", message);
    if (message.hasHeader(SagaReplyHeaders.REPLY_SAGA_ID)) {
      handleReply(message);
    } else {
      logger.warn("Handle message doesn't know what to do with: {} ", message);
    }
  }


  private void handleReply(Message message) {

    if (!isReplyForThisSagaType(message))
      return;

    logger.debug("Handle reply: {}", message);

    String sagaId = message.getRequiredHeader(SagaReplyHeaders.REPLY_SAGA_ID);
    String sagaType = message.getRequiredHeader(SagaReplyHeaders.REPLY_SAGA_TYPE);

    SagaInstance sagaInstance = sagaInstanceRepository.find(sagaType, sagaId);
    Data sagaData = SagaDataSerde.deserializeSagaData(sagaInstance.getSerializedSagaData());


    message.getHeader(SagaReplyHeaders.REPLY_LOCKED).ifPresent(lockedTarget -> {
      String destination = message.getRequiredHeader(CommandMessageHeaders.inReply(CommandMessageHeaders.DESTINATION));
      sagaInstance.addDestinationsAndResources(singleton(new DestinationAndResource(destination, lockedTarget)));
    });

    String currentState = sagaInstance.getStateName();

    logger.info("Current state={}", currentState);

    SagaActions<Data> actions = getStateDefinition().handleReply(currentState, sagaData, message);

    logger.info("Handled reply. Sending commands {}", actions.getCommands());

    processActions(sagaId, sagaInstance, sagaData, actions);


  }

  private void processActions(String sagaId, SagaInstance sagaInstance, Data sagaData, SagaActions<Data> actions) {


    while (true) {

      if (actions.getLocalException().isPresent()) {

        actions = getStateDefinition().handleReply(actions.getUpdatedState().get(), actions.getUpdatedSagaData().get(), MessageBuilder
                .withPayload("{}")
                .withHeader(ReplyMessageHeaders.REPLY_OUTCOME, CommandReplyOutcome.FAILURE.name())
                .withHeader(ReplyMessageHeaders.REPLY_TYPE, Failure.class.getName())
                .build());


      } else {
        // only do this if successful

    	/***
    	actions.getCommands() can be null. if it's null, then it should be a local action and no commands is really sent out for local action.  
    	
    	If it sends command to remote service, then remote service sends back reply message to this.makeSagaReplyChannel(): 
    	
    	 net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga + "-reply"
    	 
    	 Note this reply channel is subscribed by SagaManagerImpl.subscribeToReplyChannel at SagaInstanceFactory.makeSagaManager.
    	 
    	 SagaInstanceFactory.makeSagaManage is called at OrderService.createOrder.  
    	 
    	   @PostConstruct
		  public void subscribeToReplyChannel() {
		    messageConsumer.subscribe(saga.getSagaType() + "-consumer", singleton(makeSagaReplyChannel()),
		            this::handleMessage);
		  }
    	 
    	 saga.getSagaType() + "-consumer" is 
    	 
    	 net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga + "-consumer" is subscriber id. 
    	 
    	 handleMessage -> handleReply
    	 
    	 Check handleReply, it will call processActions again. Sending a command to a remote service will break 
    	 this while loop, it waits for remote service to send back a reply message and handleReply message and 
    	 processActions accordingly. 
    	 
    	 
    	***/
        String lastRequestId = sagaCommandProducer.sendCommands(this.getSagaType(), sagaId, actions.getCommands(), this.makeSagaReplyChannel());
        sagaInstance.setLastRequestId(lastRequestId);

        updateState(sagaInstance, actions);

        sagaInstance.setSerializedSagaData(SagaDataSerde.serializeSagaData(actions.getUpdatedSagaData().orElse(sagaData)));

        if (actions.isEndState()) {
          performEndStateActions(sagaId, sagaInstance, actions.isCompensating(), sagaData);
        }

        sagaInstanceRepository.update(sagaInstance);

        if (!actions.isLocal())
          break;

        /***
        It must be a local actions if coming here. In this case, localFunction has already been performed. We should process next action. 
        Therefore, we call getStateDefinition().handleReply to create a new action (it could be a local or remote action). 
        
        Note that even there's no registered reply handler, we can still produce a next action
        
          @Override
		  public SagaActions<Data> SimpleSagaDefinition.handleReply(String currentState, Data sagaData, Message message) {
		
		    SagaExecutionState state = SagaExecutionStateJsonSerde.decodeState(currentState);
		    SagaStep<Data> currentStep = sagaSteps.get(state.getCurrentlyExecuting());
		    boolean compensating = state.isCompensating();
		
		    currentStep.getReplyHandler(message, compensating).ifPresent(handler -> {
		      invokeReplyHandler(message, sagaData, handler);
		    });
		
		    if (currentStep.isSuccessfulReply(compensating, message)) {
		      return executeNextStep(sagaData, state);
		    } else if (compensating) {
		      throw new UnsupportedOperationException("Failure when compensating");
		    } else {
		      return executeNextStep(sagaData, state.startCompensating());
		    }
		  }
		  
		  executeNextStep creates new action which runs this while loop again. 
        
        ***/
        actions = getStateDefinition().handleReply(actions.getUpdatedState().get(), actions.getUpdatedSagaData().get(), MessageBuilder
                .withPayload("{}")
                .withHeader(ReplyMessageHeaders.REPLY_OUTCOME, CommandReplyOutcome.SUCCESS.name())
                .withHeader(ReplyMessageHeaders.REPLY_TYPE, Success.class.getName())
                .build());
      }
    }
  }

  private void updateState(SagaInstance sagaInstance, SagaActions<Data> actions) {
    actions.getUpdatedState().ifPresent(stateName -> {
      sagaInstance.setStateName(stateName);
      sagaInstance.setEndState(actions.isEndState());
      sagaInstance.setCompensating(actions.isCompensating());
    });
  }


  private Boolean isReplyForThisSagaType(Message message) {
    return message.getHeader(SagaReplyHeaders.REPLY_SAGA_TYPE).map(x -> x.equals(getSagaType())).orElse(false);
  }


}

