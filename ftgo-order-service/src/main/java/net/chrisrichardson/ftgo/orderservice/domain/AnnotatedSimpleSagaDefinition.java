package net.chrisrichardson.ftgo.orderservice.domain;

package io.eventuate.tram.sagas.simpledsl;

import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.orchestration.SagaActions;
import io.eventuate.tram.sagas.orchestration.SagaDefinition;
import io.eventuate.tram.sagas.simpledsl.SagaExecutionState;

import static io.eventuate.tram.sagas.simpledsl.SagaExecutionStateJsonSerde.encodeState;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

public class AnnotatedSimpleSagaDefinition<Data> implements SagaDefinition<Data> {
  private List<SagaStep<Data>> sagaSteps;

  public SimpleSagaDefinition(List<SagaStep<Data>> sagaSteps) {
    this.sagaSteps = sagaSteps;
  }

  /***
    data is new CreateOrderSagaState(order.getId(), orderDetails);
  ***/
  @Override
  public SagaActions<Data> start(Data sagaData) {
    SagaExecutionState currentState = new SagaExecutionState(-1, false);

    StepToExecute<Data> stepToExecute = nextStepToExecute(currentState, sagaData);

    if (stepToExecute.isEmpty()) {
      return makeEndStateSagaActions(currentState);
    } else
      return stepToExecute.executeStep(sagaData, currentState);
    
    /***
      public SagaActions<Data> StepToExecute.executeStep(Data data, SagaExecutionState currentState) {
    SagaExecutionState newState = currentState.nextState(size());
    SagaActions.Builder<Data> builder = SagaActions.builder();
    boolean compensating = currentState.isCompensating();

    step.get().makeStepOutcome(data, this.compensating).visit(builder::withIsLocal, builder::withCommands);

    return builder
            .withUpdatedSagaData(data)
            .withUpdatedState(encodeState(newState))
            .withIsEndState(newState.isEndState())
            .withIsCompensating(compensating)
            .build();
  }
    ***/
  }

  @Override
  public SagaActions<Data> handleReply(String currentState, Data sagaData, Message message) {

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



  private StepToExecute<Data> nextStepToExecute(SagaExecutionState state, Data data) {
    int skipped = 0;
    /// if invoked by start method, then compensating is false
    boolean compensating = state.isCompensating();
    int direction = compensating ? -1 : +1;
    for (int i = state.getCurrentlyExecuting() + direction; i >= 0 && i < sagaSteps.size(); i = i + direction) {
      SagaStep<Data> step = sagaSteps.get(i);
      if ((compensating ? step.hasCompensation(data) : step.hasAction(data))) {
        return new StepToExecute<>(Optional.of(step), skipped, compensating);
      } else
        skipped++;
    }
    return new StepToExecute<>(Optional.empty(), skipped, compensating);
  }

  private SagaActions<Data> executeNextStep(Data data, SagaExecutionState state) {
    StepToExecute<Data> stepToExecute = nextStepToExecute(state, data);
    if (stepToExecute.isEmpty()) {
      return makeEndStateSagaActions(state);
    } else {
      // do something
      return stepToExecute.executeStep(data, state);
    }
  }

  private void invokeReplyHandler(Message message, Data data, BiConsumer<Data, Object> handler) {
    Class m;
    try {
      m = Class.forName(message.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE));
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    Object reply = JSonMapper.fromJson(message.getPayload(), m);
    handler.accept(data, reply);
  }

  private SagaActions<Data> makeEndStateSagaActions(SagaExecutionState state) {
    return SagaActions.<Data>builder()
            .withUpdatedState(SagaExecutionStateJsonSerde.encodeState(SagaExecutionState.makeEndState()))
            .withIsEndState(true)
            .withIsCompensating(state.isCompensating())
            .build();
  }


}
