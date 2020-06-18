package net.chrisrichardson.ftgo.kitchenservice.contract;

import io.eventuate.tram.springcloudcontractsupport.EventuateContractVerifierConfiguration;
import net.chrisrichardson.ftgo.kitchenservice.api.TicketDetails;
import net.chrisrichardson.ftgo.kitchenservice.domain.KitchenService;
import net.chrisrichardson.ftgo.kitchenservice.domain.Ticket;
import net.chrisrichardson.ftgo.kitchenservice.messagehandlers.KitchenServiceMessageHandlersConfiguration;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Collections;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = AnnotatedAbstractKitchenServiceConsumerContractTest.TestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureMessageVerifier
public abstract class AnnotatedAbstractKitchenServiceConsumerContractTest {

  @Configuration
  @Import({KitchenServiceMessageHandlersConfiguration.class, EventuateContractVerifierConfiguration.class})
  public static class TestConfiguration {

	///Overrides the definition of the kitchenService @Bean with a mock
    @Bean
    public KitchenService kitchenService() {
      return mock(KitchenService.class);
    }

  }

  @Autowired
  private KitchenService kitchenService;

  @Before
  public void setup() {
     reset(kitchenService);
     /*** 
      * Configures the mock to return the values that matche a contract (CreateTicket.groovy)'s ouptut message
      * Note that this is KitchenService rather than KitchenServiceProxy
      * KitchenServiceCommandHandler invokes KitchenService with arguments that are derived from a contract's 
      * input message, and then creates a reply message that's derived from the return value. 
      * The setup method configures the mock KitchenService that match the contract's output message.
     ***/
     when(kitchenService.createTicket(eq(1L), eq(99L), any(TicketDetails.class)))
             .thenReturn(new Ticket(1L, 99L, new TicketDetails(Collections.emptyList())));
  }

}
