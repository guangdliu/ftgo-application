package net.chrisrichardson.ftgo.restaurantservice.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import net.chrisrichardson.ftgo.restaurantservice.aws.AbstractHttpHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/***
The AbstractAutowiringHttpRequestHandler implmenets dependency injection for request handlers.
It  creates an ApplicationContext using SpringApplication.run() and autowires dependencies prior
to handling the first request. Subclasses such as FindRestaurantRequestHandler must implement
the getApplicationContext() method.
***/
public abstract class AnnotatedAbstractAutowiringHttpRequestHandler extends AbstractHttpHandler {

  private static ConfigurableApplicationContext ctx;
  private ReentrantReadWriteLock ctxLock = new ReentrantReadWriteLock();
  private boolean autowired = false;

  /// 1 Create the Spring Boot Application context just once.
  protected synchronized ApplicationContext getAppCtx() {
    ctxLock.writeLock().lock();
    try {
      if (ctx == null) {
        ctx =  SpringApplication.run(getApplicationContextClass());
      }
      return ctx;
    } finally {
      ctxLock.writeLock().unlock();
    }
  }

  ///3. Returns the @configuration class used to create ApplicationContext
  protected abstract Class<?> getApplicationContextClass();

  @Override
  protected void beforeHandling(APIGatewayProxyRequestEvent request, Context context) {
	/// 2. Injects dependencies into the request handler using autowiring before handling the first request.
    super.beforeHandling(request, context);
    if (!autowired) {
      getAppCtx().getAutowireCapableBeanFactory().autowireBean(this);
      autowired = true;
    }
  }
}
