package net.chrisrichardson.ftgo.orderservice.sagas.createorder;

import io.eventuate.tram.commands.common.Command;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.orchestration.SagaActions;
import io.eventuate.tram.sagas.orchestration.SagaDefinition;
import io.eventuate.tram.sagas.simpledsl.CommandEndpoint;
import io.eventuate.tram.sagas.simpledsl.InvokeParticipantStepBuilder;
import io.eventuate.tram.sagas.simpledsl.ParticipantInvocationStep;
import io.eventuate.tram.sagas.simpledsl.SagaExecutionState;
import io.eventuate.tram.sagas.simpledsl.SagaExecutionStateJsonSerde;
import io.eventuate.tram.sagas.simpledsl.SagaStep;
import io.eventuate.tram.sagas.simpledsl.SimpleSaga;
import io.eventuate.tram.sagas.simpledsl.SimpleSagaDefinitionBuilder;
import io.eventuate.tram.sagas.simpledsl.StepBuilder;
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.*;
import net.chrisrichardson.ftgo.kitchenservice.api.CreateTicketReply;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnnotatedCreateOrderSaga implements SimpleSaga<CreateOrderSagaState> {


  private Logger logger = LoggerFactory.getLogger(getClass());

  private SagaDefinition<CreateOrderSagaState> sagaDefinition;

  public AnnotatedCreateOrderSaga(OrderServiceProxy orderService, ConsumerServiceProxy consumerService, KitchenServiceProxy kitchenService,
                         AccountingServiceProxy accountingService) {
	  
	         /***
	         SagaDefinition is built step by step. A step can be a ParticipantInvocationStep or a LocalStep. 
	         A step is added to SagaDefinition.sagaSteps. 
	         ParticipantInvocationStep is added to SagaDefinition.sagaSteps by
	         InvokeParticipantStepBuilder.step() or build(). 
	         LocalStep is added to SagaDefinition.sagaSteps by 
	         LocalStepBuilder.step() or build(). 
	         +StepBuilder.step() returns a StepBuilder. 

			 StepBuilder.withCompensation and StepBuilder.invokeParticipant are used to start a new Step
			 InvokeParticipantStepBuilder.onReply() and InvokeParticipantStepBuilder.withCompensation are used to update an existing step. 	         
             InvokeParticipantStepBuilder.step() or build() adds an existing step to SagaDefinition.sagaSteps. 
             
             Below are some details. 
             
             
             StepBuilder<Data>.withCompensation. It creates a new InvokeParticipantStepBuilder.
               
              @Override
			  public <C extends Command> InvokeParticipantStepBuilder<Data> StepBuilder.withCompensation(CommandEndpoint<C> commandEndpoint, Function<Data, C> commandProvider) {
			    return new InvokeParticipantStepBuilder<>(parent).withCompensation(commandEndpoint, commandProvider);
			  }
			               
	          public StepBuilder<Data> InvokeParticipantStepBuilder.step() {
			    addStep();
			    return new StepBuilder<>(parent);
			  }
			  
		    private void InvokeParticipantStepBuilder.addStep() {
				parent.addStep(new ParticipantInvocationStep<>(action, compensation, actionReplyHandlers, compensationReplyHandlers));
			}
			
			Note that addStep invokes parent.addStep which really adds a step to SimpleSagaDefinition.sagaSteps
			LocalStepBuilder.step() and InvokeParticipantStepBuilder.step() are the ones to create a step instead of StepBuilder. 
			Actually, StepBuilder does not even have a step() method. 
			
			LocalStepBuilder.step() and InvokeParticipantStepBuilder.step() returns a new StepBuilder<>(parent) which I guess is used to
			create a new step. 
			
			StepBuilder.withCompensation() and StepBuilder.invokeParticipant returns a new InvokeParticipantStepBuilder.
			InvokeParticipantStepBuilder.onReply() and InvokeParticipantStepBuilder.withCompensation returns 'this'
			
			StepBuilder.withCompensation and StepBuilder.invokeParticipant are used to start a new Step
			InvokeParticipantStepBuilder.onReply() and InvokeParticipantStepBuilder.withCompensation are used to update an existing step. 
            ***/
	 
	
    this.sagaDefinition =
             step()
             /*** 
              Zero Step:
              * OrderService.createOrder just created an order locally. Other services need to do sth. 
              * SimpleSagaDsl.step creates a SimpleSagaDefinitionBuilder but returns a StepBuilder. 
              * SimpleSagaDefinitionBuilder is used as the parent of the StepBuilder.
		      
		      public interface SimpleSagaDsl<Data> {
				  default StepBuilder<Data> step() {
				    SimpleSagaDefinitionBuilder<Data> builder = new SimpleSagaDefinitionBuilder<>();
				    return new StepBuilder<>(builder);
				  }
			  }
			 
			 Note that SimpleSagaDsl.step() won't add a step to SimpleSagaDefinition.sagaSteps
			 You can consider this step is dummy step which is just for a StepBuilder

             ***/
             .withCompensation(orderService.reject, CreateOrderSagaState::makeRejectOrderCommand)
            .step()
            /***
			First Step 
			            
			We've done some local stuff, .e.g save the new order in local db;
			If remote services failed, we want to revert the local stuff. 
			We should makeRejectOrderCommand. This command is eventually handled by 
			OrderCommandHandlers.rejectOrder -> orderService.rejectOrder which will
			update this order as rejected instead of deleting it directly.  
			
			First step does not have an action.
			When we execute SimpleSagaDefinition.start -> SimpleSagaDefinition.nextStepToExecute,
			it actually skips the first step and goes to the second step.
	  
            ***/
            .invokeParticipant(consumerService.validateOrder, CreateOrderSagaState::makeValidateOrderByConsumerCommand)
            .step()
            /***
            Second Step:
            consumerService.validateOrder is eventullay handled by ConsumerServiceCommandHandlers.validateOrderForConsumer. 
            If verified, then a success message is sent back. 
            If   failed, then a Failure message is sent back. 
            
            The reply  message is handled by the following flow:
            SagaManagerImpl.handleReply -> SimpleSagaDefinition.handleReply(String currentState, Data sagaData, Message message). 
            
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
			  
			  Even though the second step doesn't define a reply handler, if you check the if block above,
			  it will execute
			    executeNextStep(sagaData, state.startCompensating());
			    
			  state.startCompensating() results in the execution of first step's compensating function,

            ***/
              .invokeParticipant(kitchenService.create, CreateOrderSagaState::makeCreateTicketCommand)
              .onReply(CreateTicketReply.class, CreateOrderSagaState::handleCreateTicketReply)
              .withCompensation(kitchenService.cancel, CreateOrderSagaState::makeCancelCreateTicketCommand)
            .step()
            /***
            Third Step:
            OrderService sends a makeCreateTicketCommand to kitchen service. 
            KitcheServiceCommandHandlers.createTicket is invoked to create a new Ticket. 
            If a new ticket is created successfully, then a SUCESS message is sent back. 
            If it failed, then a FAILURE message is sent back. 
            
            If success, then 
            
            	currentStep.getReplyHandler(message, compensating).ifPresent(handler -> {
			      invokeReplyHandler(message, sagaData, handler);
			    });
			    
			CreateOrderSagaState::handleCreateTicketReply is invoked to populate CreateOrderSagaState with ticket id  
            
            
            If failed, then 
            
             else {
			      return executeNextStep(sagaData, state.startCompensating());
			    }
			    
			 This results in  CreateOrderSagaState::makeCancelCreateTicketCommand. 
			 OrderService sends  CancelCreateTicketCommand to KitcheService.
			 KticheServiceCommandHandlers.cancelCreateTicket(Long ticketId) is then executed. 
			 Then KitcheService sends a SUCESS message back to OrderService, 
			 for this success message:
			 
			 
			    if (currentStep.isSuccessfulReply(compensating, message)) {
			      return executeNextStep(sagaData, state);
			    }
			    
			 is executed, note that compensating is true because of the 
			 failure message for CreateTicektCommand. 
			 
			 Therefore 
			 executeNextStep(sagaData, state); causes compensating actions
			 for each previous step executed. 
			 
            
            ***/
                .invokeParticipant(accountingService.authorize, CreateOrderSagaState::makeAuthorizeCommand)
            .step()
            /***
            Fourth Step:
            Authorized the credit card by calling accountingService. 
            If success, go straight forward. 
            If failed, it will execute
            
            else{
			    executeNextStep(sagaData, state.startCompensating());
			}
			in the if block 
			    
			state.startCompensating() results in the execution of third step's compensating function
			  
			  	.withCompensation(kitchenService.cancel, CreateOrderSagaState::makeCancelCreateTicketCommand)
			  	
			  	
			also, results in the execution of first step's compensating function
            
                .withCompensation(orderService.reject, CreateOrderSagaState::makeRejectOrderCommand)
            
            ***/
            .invokeParticipant(kitchenService.confirmCreate, CreateOrderSagaState::makeConfirmCreateTicketCommand)
            .step()
            /***
            Fifth Step:
            
            5th and 6th step are similar to 4th step. 
            ***/
              .invokeParticipant(orderService.approve, CreateOrderSagaState::makeApproveOrderCommand)
            .build();
            /***
            Sixth Step:
            ***/
    	
    

  }


  @Override
  public SagaDefinition<CreateOrderSagaState> getSagaDefinition() {
    return sagaDefinition;
  }


}
