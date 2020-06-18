package net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import net.chrisrichardson.ftgo.cqrs.orderhistory.OrderHistoryDao;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/***
Spring Boot reads properties from a variety of sources: e.g.
1. Command-line arguments
2. SPRING_APPLICATION_JSON, an operating system environment variable,
or JVM system property that contains JSON
3. JVM System Properties
4. Operating system environment variables
5. A configuration file in the current directory
1 > 2 > 3 > 4 > 5

Spring Boot makes these properties availalbe to Spring Framework's 
ApplicationContext. A service can objtain the value of a property
using the @Value annotation.

***/
@Configuration
public class AnnotatedOrderHistoryDynamoDBConfiguration {

  @Value("${aws.dynamodb.endpoint.url:#{null}}")
  private String awsDynamodbEndpointUrl;

  @Value("${aws.region}")
  private String awsRegion;

  @Value("${aws.access.key_id:null}")
  private String accessKey;

  @Value("${aws.secret.access.key:null}")
  private String secretKey;

  @Bean
  public AmazonDynamoDB amazonDynamoDB() {

    if (!StringUtils.isBlank(awsDynamodbEndpointUrl)) {
      return AmazonDynamoDBClientBuilder
          .standard()
          .withEndpointConfiguration(
              new AwsClientBuilder.EndpointConfiguration(awsDynamodbEndpointUrl, awsRegion))
          .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
          .build();
    } else {
      return AmazonDynamoDBClientBuilder
              .standard()
              .withRegion(awsRegion)
              .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
              .build();
    }
  }

  @Bean
  public DynamoDB dynamoDB(AmazonDynamoDB client) {
    return   new DynamoDB(client);
  }

  @Bean
  public OrderHistoryDao orderHistoryDao(AmazonDynamoDB client, DynamoDB dynamoDB) {
    return new OrderHistoryDaoDynamoDb(dynamoDB);
  }

  @Bean
  public HealthIndicator dynamoDBHealthIndicator(DynamoDB dynamoDB) {
    return new DynamoDBHealthIndicator(dynamoDB);
  }
}
