package net.chrisrichardson.ftgo.orderservice.sagaparticipants;

import io.eventuate.tram.commands.producer.TramCommandProducerConfiguration;
import io.eventuate.tram.inmemory.TramInMemoryConfiguration;
import io.eventuate.tram.messaging.common.ChannelMapping;
import io.eventuate.tram.messaging.common.DefaultChannelMapping;
import io.eventuate.tram.sagas.orchestration.SagaCommandProducer;
import io.eventuate.tram.springcloudcontractsupport.EventuateContractVerifierConfiguration;
import io.eventuate.tram.springcloudcontractsupport.EventuateTramRoutesConfigurer;
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother;
import net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga;
import net.chrisrichardson.ftgo.kitchenservice.api.CreateTicket;
import net.chrisrichardson.ftgo.kitchenservice.api.CreateTicketReply;
import net.chrisrichardson.ftgo.kitchenservice.api.TicketDetails;
import net.chrisrichardson.ftgo.kitchenservice.api.TicketLineItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.BatchStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import javax.sql.DataSource;
import java.util.Collections;

import static net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.CHICKEN_VINDALOO_QUANTITY;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_ID;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.CHICKEN_VINDALOO;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.CHICKEN_VINDALOO_MENU_ITEM_ID;
import static org.junit.Assert.assertEquals;

/***
Figure 10.5, the contracts are used to test the adapter classes (KtichenServiceProxy
and KitchenServiceCommnadHanlders) interaction. The provider-side tests verify 
that KitchenServiceCommandHandler handles commands and sends back replies. 
The consumer-side tests verify that KichenServiceProxy sends correctly structured 
commands that 
conform to the contract, and that it correctly handles the reply messages.

In this example, KichenServiceProxyTest tests KitchenServiceProxy. It uses
Spring Clund Contract to configure messaging stubs that verify that the command
message matches a contract's input message and replies with the courresponding output
messages. The contract file is CreateTicket.groovy


***/
@RunWith(SpringRunner.class)
@SpringBootTest(classes= AnnotatedKitchenServiceProxyIntegrationTest.TestConfiguration.class,
        webEnvironment= SpringBootTest.WebEnvironment.NONE)
/// 1. Confiure the stub KitchenService to respond to messages.
@AutoConfigureStubRunner(ids =
        {"net.chrisrichardson.ftgo:ftgo-kitchen-service-contracts"}
        )
@DirtiesContext
public class AnnotatedKitchenServiceProxyIntegrationTest {


  @Configuration
  @EnableAutoConfiguration
  @Import({TramCommandProducerConfiguration.class,
          TramInMemoryConfiguration.class, EventuateContractVerifierConfiguration.class})
  public static class TestConfiguration {

    @Bean
    public DataSource dataSource() {
      EmbeddedDatabaseBuilder builder = new EmbeddedDatabaseBuilder();
      return builder.setType(EmbeddedDatabaseType.H2)
              .addScript("eventuate-tram-embedded-schema.sql")
              .addScript("eventuate-tram-sagas-embedded.sql")
              .build();
    }


    @Bean
    public EventuateTramRoutesConfigurer eventuateTramRoutesConfigurer(BatchStubRunner batchStubRunner) {
      return new EventuateTramRoutesConfigurer(batchStubRunner);
    }

    @Bean
    public SagaMessagingTestHelper sagaMessagingTestHelper() {
      return new SagaMessagingTestHelper();
    }

    @Bean
    public SagaCommandProducer sagaCommandProducer() {
      return new SagaCommandProducer();
    }

    @Bean
    public KitchenServiceProxy kitchenServiceProxy() {
      return new KitchenServiceProxy();
    }
  }

  @Autowired
  private SagaMessagingTestHelper sagaMessagingTestHelper;

  @Autowired
  private KitchenServiceProxy kitchenServiceProxy;

  @Test
  public void shouldSuccessfullyCreateTicket() {
    CreateTicket command = new CreateTicket(AJANTA_ID, OrderDetailsMother.ORDER_ID,
            new TicketDetails(Collections.singletonList(new TicketLineItem(CHICKEN_VINDALOO_MENU_ITEM_ID, CHICKEN_VINDALOO, CHICKEN_VINDALOO_QUANTITY))));
    CreateTicketReply expectedReply = new CreateTicketReply(OrderDetailsMother.ORDER_ID);
    String sagaType = CreateOrderSaga.class.getName();

    ///2. Send the command and wait for a reply
    CreateTicketReply reply = sagaMessagingTestHelper.sendAndReceiveCommand(kitchenServiceProxy.create, command, CreateTicketReply.class, sagaType);

    ///3. Verify the reply.
    assertEquals(expectedReply, reply);

  }

}