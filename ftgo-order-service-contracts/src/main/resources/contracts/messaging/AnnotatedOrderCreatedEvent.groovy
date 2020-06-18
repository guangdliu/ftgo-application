package contracts.messaging;

/***
The contract for publishing an OrderCreated event.
It specifies the event's channel, 
along with the expected body and message headers.

OrderService publishes Order* events whenever it creates or updates an order aggregate.
OrderHistoryService is one of the consumers of those events. 

The contract are used to test both sides of the publish/subscribe interaction.
The provider-side tests verify that OrderDomainEventPublisher publishes events
that confirm to the contract. 
The consumer-side tests verify that OrderHistoryEventHandlers consume the example
events from the contract.

Each consumer-side test (e.g. OrderHistoryEventHandlerTest)
 publishes the event specified by the contract (to the channel),
and verifies that OrderHistoryEventHandlers invokes its mocked dependencies correctly. 

***/
org.springframework.cloud.contract.spec.Contract.make {
    label 'orderCreatedEvent' /// 1. used by the consumer test to trigger the event to be published
    input {
        triggeredBy('orderCreated()') /// 2. invoked by the code generated provider test
    }

    outputMessage {
        sentTo('net.chrisrichardson.ftgo.orderservice.domain.Order')
        body('''{"orderDetails":{"lineItems":[{"quantity":5,"menuItemId":"1","name":"Chicken Vindaloo","price":"12.34","total":"61.70"}],"orderTotal":"61.70","restaurantId":1, "consumerId":1511300065921}, "restaurantName" : "Ajanta"}''')
        headers {
            header('event-aggregate-type', 'net.chrisrichardson.ftgo.orderservice.domain.Order')
            header('event-type', 'net.chrisrichardson.ftgo.orderservice.api.events.OrderCreatedEvent')
            header('event-aggregate-id', '99') // Matches OrderDetailsMother.ORDER_ID
        }
    }
}