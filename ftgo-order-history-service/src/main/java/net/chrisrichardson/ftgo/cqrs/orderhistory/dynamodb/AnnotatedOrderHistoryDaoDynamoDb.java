package net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb;


import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Index;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.RangeKeyCondition;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.chrisrichardson.ftgo.common.Money;
import net.chrisrichardson.ftgo.cqrs.orderhistory.Location;
import net.chrisrichardson.ftgo.cqrs.orderhistory.OrderHistory;
import net.chrisrichardson.ftgo.cqrs.orderhistory.OrderHistoryDao;
import net.chrisrichardson.ftgo.cqrs.orderhistory.OrderHistoryFilter;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderLineItem;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderState;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.BreakIterator;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class AnnotatedOrderHistoryDaoDynamoDb implements OrderHistoryDao {

  private Logger logger = LoggerFactory.getLogger(getClass());

  public static final String FTGO_ORDER_HISTORY_BY_ID = "ftgo-order-history";
  public static final String FTGO_ORDER_HISTORY_BY_CONSUMER_ID_AND_DATE =
          "ftgo-order-history-by-consumer-id-and-creation-time";
  public static final String ORDER_STATUS_FIELD = "orderStatus";
  private static final String DELIVERY_STATUS_FIELD = "deliveryStatus";

  private final DynamoDB dynamoDB;

  private Table table;
  private Index index;

  public AnnotatedOrderHistoryDaoDynamoDb(DynamoDB dynamoDB) {
    this.dynamoDB = dynamoDB;
    table = this.dynamoDB.getTable(FTGO_ORDER_HISTORY_BY_ID);
    index = table.getIndex(FTGO_ORDER_HISTORY_BY_CONSUMER_ID_AND_DATE);
  }

  /***
  UPDATING ORDERS
DynamoDB supports two operations for adding and updating items: PutItem() and
UpdateItem(). The PutItem() operation creates or replaces an entire item by its
primary key. In theory, OrderHistoryDaoDynamoDb could use this operation to insert
and update orders. One challenge, however, with using PutItem() is ensuring that
simultaneous updates to the same item are handled correctly.

Consider, for example, the scenario where two event handlers simultaneously
attempt to update the same item. Each event handler calls OrderHistoryDaoDynamoDb
to load the item from DynamoDB, change it in memory, and update it in DynamoDB
using PutItem(). One event handler could potentially overwrite the change made by
the other event handler. OrderHistoryDaoDynamoDb can prevent lost updates by using
DynamoDB’s optimistic locking mechanism. But an even simpler and more efficient
approach is to use the UpdateItem() operation.

The UpdateItem() operation updates individual attributes of the item, creating
the item if necessary. Since different event handlers update different attributes of the
Order item, using UpdateItem makes sense. This operation is also more efficient
because there’s no need to first retrieve the order from the table.
  
  ***/
  @Override
  public boolean addOrder(Order order, Optional<SourceEvent> eventSource) {
	///looks like DynamoDB is a key, value data store
	///key is orderId and creationDate
	/// value is json: {orderStatus: APPROVED, keywords: ["A", "B", "C"], restaurantId: }
    UpdateItemSpec spec = new UpdateItemSpec()
            .withPrimaryKey("orderId", order.getOrderId())
            .withUpdateExpression("SET orderStatus = :orderStatus, " + ///here we specify what colums are going to be updated
                    "creationDate = :creationDate, consumerId = :consumerId, lineItems =" +
                    " :lineItems, keywords = :keywords, restaurantId = :restaurantId, " +
                    " restaurantName = :restaurantName"
            )
            .withValueMap(new Maps() /// here we provide the values of columns above
                    .add(":orderStatus", order.getStatus().toString())
                    .add(":consumerId", order.getConsumerId())
                    .add(":creationDate", order.getCreationDate().getMillis())
                    .add(":lineItems", mapLineItems(order.getLineItems()))
                    .add(":keywords", mapKeywords(order))
                    .add(":restaurantId", order.getRestaurantId())
                    .add(":restaurantName", order.getRestaurantName())
                    .map())
            .withReturnValues(ReturnValue.NONE);
    return idempotentUpdate(spec, eventSource);
  }

  /***
If the sourceEvent is supplied, idempotentUpdate() invokes SourceEvent.add-
DuplicateDetection() to add to UpdateItemSpec the condition expression that was
described earlier. The idempotentUpdate() method catches and ignores the
ConditionalCheckFailedException, which is thrown by updateItem() if the event
was a duplicate. 

OrderHistoryDaoDynamoDb can detect duplicate
events by recording in each item the events that have caused it to be updated. It can
then use the UpdateItem() operation’s conditional update mechanism to only update
an item if an event isn’t a duplicate.

A conditional update is only performed if a condition expression is true. A condition
expression tests whether an attribute exists or has a particular value. The Order-
HistoryDaoDynamoDb DAO can track events received from each aggregate instance
using an attribute called «aggregateType»«aggregateId» whose value is the highest
received event ID. An event is a duplicate if the attribute exists and its value is less
than or equal to the event ID. The OrderHistoryDaoDynamoDb DAO uses this condition
expression:

attribute_not_exists(aggregateType aggregateId)
OR aggregateType aggregateId < :eventId


We rely on SourceEvent.addDuplicateDetection to detect duplication. 

  public UpdateItemSpec SourceEvent.addDuplicateDetection(UpdateItemSpec spec) {
    HashMap<String, String> nameMap = spec.getNameMap() == null ? new HashMap<>() : new HashMap<>(spec.getNameMap());
    nameMap.put("#duplicateDetection", "events." + aggregateType + aggregateId);
    HashMap<String, Object> valueMap = new HashMap<>(spec.getValueMap());
    valueMap.put(":eventId", eventId);
    return spec.withUpdateExpression(String.format("%s , #duplicateDetection = :eventId", spec.getUpdateExpression()))
            .withNameMap(nameMap)
            .withValueMap(valueMap)
            .withConditionExpression(Expressions.and(spec.getConditionExpression(), "attribute_not_exists(#duplicateDetection) OR #duplicateDetection < :eventId"));
  }
  
For example, suppose an event handler receives a DeliveryPickup event whose ID
is 123323-343434 from a Delivery aggregate whose ID is 3949384394-039434903.
The name of the tracking attribute is Delivery3949384394-039434903. The event
handler should consider the event to be a duplicate if the value of this attribute is
greater than or equal to 123323-343434. The query() operation invoked by the event
handler updates the Order item using this condition expression:
attribute_not_exists(Delivery3949384394-039434903)
OR Delivery3949384394-039434903 < :eventId

nameMap {"#duplicateDetection": "events.Delivery3949384394-039434903"}
valueMap has one more entry {":eventId": "123323-343434"}

conditional expression can be translated as below:

if "events.Delivery3949384394-039434903" attribute doesn't exist, return true.
or "events.Delivery3949384394-039434903" attribute's value (which should be an eventId) < 123323-343434, which means it's the newest event,  return true.


  ***/
  private boolean idempotentUpdate(UpdateItemSpec spec, Optional<SourceEvent>
          eventSource) {
    try {
      table.updateItem(eventSource.map(es -> es.addDuplicateDetection(spec))
              .orElse(spec));
      return true;
    } catch (ConditionalCheckFailedException e) {
      logger.error("not updated {}", eventSource);
      // Do nothing
      return false;
    }
  }

////  @Override
//  public void addOrderV1(Order order, Optional<SourceEvent> eventSource) {
//    Map<String, AttributeValue> keyMapBuilder = makeKey1(order.getOrderId());
//    AvMapBuilder expressionAttrs = new AvMapBuilder(":orderStatus", new
// AttributeValue(order.getStatus().toString()))
//            .add(":cd", new AttributeValue().withN(Long.toString(order
// .getCreationDate().getMillis())))
//            .add(":consumerId", order.getConsumerId())
//            .add(":lineItems", mapLineItems(order.getLineItems()))
//            .add(":keywords", mapKeywords(order))
//            .add(":restaurantName", order.getRestaurantId())
//            ;
//
//
//    UpdateItemRequest uir = new UpdateItemRequest()
//            .withTableName(FTGO_ORDER_HISTORY_BY_ID)
//            .withKey(keyMapBuilder)
//            .withUpdateExpression("SET orderStatus = :orderStatus,
// creationDate = :cd, consumerId = :consumerId, lineItems = :lineItems,
// keywords = :keywords, restaurantName = :restaurantName")
//            .withConditionExpression("attribute_not_exists(orderStatus)")
//            .withExpressionAttributeValues(expressionAttrs.map());
//    try {
//      client.updateItem(uir);
//    } catch (ConditionalCheckFailedException e) {
//      // Do nothing
//    }
//  }

  private Set mapKeywords(Order order) {
    Set<String> keywords = new HashSet<>();
    keywords.addAll(tokenize(order.getRestaurantName()));
    keywords.addAll(tokenize(order.getLineItems().stream().map
            (OrderLineItem::getName).collect(toList())));
    return keywords;
  }

  private Set<String> tokenize(Collection<String> text) {
    return text.stream().flatMap(t -> tokenize(t).stream()).collect(toSet());
  }

  private Set<String> tokenize(String text) {
    Set<String> result = new HashSet<>();
    BreakIterator bi = BreakIterator.getWordInstance();
    bi.setText(text);
    int lastIndex = bi.first();
    while (lastIndex != BreakIterator.DONE) {
      int firstIndex = lastIndex;
      lastIndex = bi.next();
      if (lastIndex != BreakIterator.DONE
              && Character.isLetterOrDigit(text.charAt(firstIndex))) {
        String word = text.substring(firstIndex, lastIndex);
        result.add(word);
      }
    }
    return result;
  }

  private List mapLineItems(List<OrderLineItem> lineItems) {
    return lineItems.stream().map(this::mapOrderLineItem).collect(toList());
  }
//  private AttributeValue mapLineItems(List<OrderLineItem> lineItems) {
//    AttributeValue result = new AttributeValue();
//    result.withL(lineItems.stream().map(this::mapOrderLineItem).collect
// (toList()));
//    return result;
//  }

  private Map mapOrderLineItem(OrderLineItem orderLineItem) {
    return new Maps()
            .add("menuItemName", orderLineItem.getName())
            .add("menuItemId", orderLineItem.getMenuItemId())
            .add("price", orderLineItem.getPrice().asString())
            .add("quantity", orderLineItem.getQuantity())
            .map();
  }
//  private AttributeValue mapOrderLineItem(OrderLineItem orderLineItem) {
//    AttributeValue result = new AttributeValue();
//    result.addMEntry("menuItem", new AttributeValue(orderLineItem
// .getName()));
//    return result;
//  }


  private Map<String, AttributeValue> makeKey1(String orderId) {
    return new AvMapBuilder("orderId", new AttributeValue(orderId)).map();
  }

  /***
	The CQRS view for the findOrderHistory() consumes events from multiple services,
	so it’s implemented as a standalone Order View Service. The service has an API
	that implements two operations: findOrderHistory() and findOrder(). Even though
	findOrder() can be implemented using API composition, this view provides this operation
	for free.
	
	Order History Service is
	structured as a set of modules, each of which implements a particular responsibility
	in order to simplify development and testing. The responsibility of each module is
	as follows:
	
	OrderHistoryEventHandlers: Subscribes to events published by the various services and invokes the OrderHistoryDAO 
	
	OrderHistoryQuery API module: Implements the REST endpoints described earlier
	
	OrderHistoryDataAccess: Contains the OrderHistoryDAO, which defines the 
	methods that update and query the ftgo-order-history DynamoDB table and its helper classes
	
	ftgo-order-history DynamoDB table—The table that stores the orders

	The findOrderHistory() query operation has a filter parameter that specifies the
	search criteria. One filter criterion is the maximum age of the orders to return. This is
	easy to implement because the DynamoDB Query operation’s key condition expression
	supports a range restriction on the sort key. The other filter criteria correspond to
	non-key attributes and can be implemented using a filter expression , which is a Boolean
	expression. A DynamoDB Query operation returns only those items that satisfy the filter
	expression. For example, to find Orders that are CANCELLED, the OrderHistoryDao-
	DynamoDb uses a query expression orderStatus = :orderStatus, where :orderStatus
	is a placeholder parameter.
	The keyword filter criteria is more challenging to implement. It selects orders
	whose restaurant name or menu items match one of the specified keywords. The
	OrderHistoryDaoDynamoDb enables the keyword search by tokenizing the restaurant
	name and menu items and storing the set of keywords in a set-valued attribute called
	keywords. It finds the orders that match the keywords by using a filter expression
	that uses the contains() function, for example contains(keywords, :keyword1)
	OR contains(keywords, :keyword2), where :keyword1 and :keyword2 are placeholders
	for the specified keywords.
	
  ***/
  @Override
  public OrderHistory findOrderHistory(String consumerId, OrderHistoryFilter
          filter) {

    QuerySpec spec = new QuerySpec()
            .withScanIndexForward(false) /// Specifies that query must return the orders in order of increasing age
            .withHashKey("consumerId", consumerId)
            .withRangeKeyCondition(new RangeKeyCondition("creationDate").gt
                    (filter.getSince().getMillis())); /// The maximum age of the orders to return

    filter.getStartKeyToken().ifPresent(token -> spec.withExclusiveStartKey
            (toStartingPrimaryKey(token)));

    Map<String, Object> valuesMap = new HashMap<>();

    String filterExpression = Expressions.and(/// Construct a filter expression and placeholder value map from the OrderHistoryFilter
            keywordFilterExpression(valuesMap, filter.getKeywords()),
            statusFilterExpression(valuesMap, filter.getStatus()));

    if (!valuesMap.isEmpty())
      spec.withValueMap(valuesMap);

    if (StringUtils.isNotBlank(filterExpression)) {
      spec.withFilterExpression(filterExpression);
    }

    System.out.print("filterExpression.toString()=" + filterExpression);

    filter.getPageSize().ifPresent(spec::withMaxResultSize); /// Limit the number of results if the caller has specified a page size

    ItemCollection<QueryOutcome> result = index.query(spec);

    return new OrderHistory(StreamSupport.stream(result.spliterator(), false)
            .map(this::toOrder).collect(toList()), /// Create an Order from  an item returned by the query.
            Optional.ofNullable(result.getLastLowLevelResult().getQueryResult
                    ().getLastEvaluatedKey()).map(this::toStartKeyToken));
  }

  private PrimaryKey toStartingPrimaryKey(String token) {
    ObjectMapper om = new ObjectMapper();
    Map<String, Object> map;
    try {
      map = om.readValue(token, Map.class);
    } catch (IOException e) {
      throw new RuntimeException();
    }
    PrimaryKey pk = new PrimaryKey();
    map.entrySet().forEach(key -> {
      pk.addComponent(key.getKey(), key.getValue());
    });
    return pk;
  }

  private String toStartKeyToken(Map<String, AttributeValue> lastEvaluatedKey) {
    Map<String, Object> map = new HashMap<>();
    lastEvaluatedKey.entrySet().forEach(entry -> {
      String value = entry.getValue().getS();
      if (value == null) {
        value = entry.getValue().getN();
        map.put(entry.getKey(), Long.parseLong(value));
      } else {
        map.put(entry.getKey(), value);
      }
    });
    ObjectMapper om = new ObjectMapper();
    try {
      return om.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new RuntimeException();
    }
  }

  private Optional<String> statusFilterExpression(Map<String, Object>
                                                          expressionAttributeValuesBuilder, Optional<OrderState> status) {
    return status.map(s -> {
      expressionAttributeValuesBuilder.put(":orderStatus", s.toString());
      return "orderStatus = :orderStatus";
    });
  }

  private String keywordFilterExpression(Map<String, Object>
                                                 expressionAttributeValuesBuilder, Set<String> kw) {
    Set<String> keywords = tokenize(kw);
    if (keywords.isEmpty()) {
      return "";
    }
    String cuisinesExpression = "";
    int idx = 0;
    for (String cuisine : keywords) {
      String var = ":keyword" + idx;
      String cuisineExpression = String.format("contains(keywords, %s)", var);
      cuisinesExpression = Expressions.or(cuisinesExpression, cuisineExpression);
      expressionAttributeValuesBuilder.put(var, cuisine);
    }

    return cuisinesExpression;
  }

//  @Override
//  public OrderHistory findOrderHistory(String consumerId,
// OrderHistoryFilter filter) {
//    AvMapBuilder expressionAttributeValuesBuilder = new AvMapBuilder
// (":cid", new AttributeValue(consumerId))
//            .add(":oct", new AttributeValue().withN(Long.toString(filter
// .getSince().getMillis())));
//    StringBuilder filterExpression = new StringBuilder();
//    Set<String> keywords = tokenize(filter.getKeywords());
//    if (!keywords.isEmpty()) {
//      if (filterExpression.length() > 0)
//        filterExpression.append(" AND ");
//      filterExpression.append(" ( ");
//      int idx = 0;
//      for (String cuisine : keywords) {
//        if (idx++ > 0) {
//          filterExpression.append(" OR ");
//        }
//        String var = ":keyword" + idx;
//        filterExpression.append("contains(keywords, ").append(var).append
// (')');
//        expressionAttributeValuesBuilder.add(var, cuisine);
//      }
//
//      filterExpression.append(" ) ");
//    }
//    filter.getStatus().ifPresent(status -> {
//      if (filterExpression.length() > 0)
//        filterExpression.append(" AND ");
//      filterExpression.append("orderStatus = :orderStatus");
//      expressionAttributeValuesBuilder.add(":orderStatus", status.toString
// ());
//    });
//    QueryRequest ar = new QueryRequest()
//            .withTableName(FTGO_ORDER_HISTORY_BY_ID)
//            .withIndexName(FTGO_ORDER_HISTORY_BY_CONSUMER_ID_AND_DATE)
//            .withScanIndexForward(false)
//            .withKeyConditionExpression("consumerId = :cid AND
// creationDate > :oct")
//            .withExpressionAttributeValues
// (expressionAttributeValuesBuilder.map());
//    System.out.print("filterExpression.toString()=" + filterExpression
// .toString());
//    if (filterExpression.length() > 0)
//      ar.withFilterExpression(filterExpression.toString());
//
//    QuerySpec spec = new QuerySpec();
//    ItemCollection<QueryOutcome> result = table.query(spec);
//
//    List<Map<String, AttributeValue>> items = client.query(ar).getItems();
//    return new OrderHistory(items.stream().map(this::toOrder).collect
// (toList()));
//  }

  @Override
  public boolean updateOrderState(String orderId, OrderState newState, Optional<SourceEvent> eventSource) {
    UpdateItemSpec spec = new UpdateItemSpec()
            .withPrimaryKey("orderId", orderId)
            .withUpdateExpression("SET #orderStatus = :orderStatus")
            .withNameMap(Collections.singletonMap("#orderStatus",
                    ORDER_STATUS_FIELD))
            .withValueMap(Collections.singletonMap(":orderStatus", newState.toString()))
            .withReturnValues(ReturnValue.NONE);
    return idempotentUpdate(spec, eventSource);
  }


  static PrimaryKey makePrimaryKey(String orderId) {
    return new PrimaryKey().addComponent("orderId", orderId);
  }

  @Override
  public void noteTicketPreparationStarted(String orderId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void noteTicketPreparationCompleted(String orderId) {
    throw new UnsupportedOperationException();

  }

  @Override
  public void notePickedUp(String orderId, Optional<SourceEvent> eventSource) {
    UpdateItemSpec spec = new UpdateItemSpec()
            .withPrimaryKey("orderId", orderId)
            .withUpdateExpression("SET #deliveryStatus = :deliveryStatus")
            .withNameMap(Collections.singletonMap("#deliveryStatus",
                    DELIVERY_STATUS_FIELD))
            .withValueMap(Collections.singletonMap(":deliveryStatus",
                    DeliveryStatus.PICKED_UP.toString()))
            .withReturnValues(ReturnValue.NONE);
    idempotentUpdate(spec, eventSource);
  }

  @Override
  public void updateLocation(String orderId, Location location) {
    throw new UnsupportedOperationException();

  }

  @Override
  public void noteDelivered(String orderId) {
    throw new UnsupportedOperationException();

  }

  @Override
  public Optional<Order> findOrder(String orderId) {
    Item item = table.getItem(new GetItemSpec()
            .withPrimaryKey(makePrimaryKey(orderId))
            .withConsistentRead(true));
    return Optional.ofNullable(item).map(this::toOrder);
  }


  private Order toOrder(Item avs) {
    Order order = new Order(avs.getString("orderId"),
            avs.getString("consumerId"),
            OrderState.valueOf(avs.getString("orderStatus")),
            toLineItems2(avs.getList("lineItems")),
            null,
            avs.getLong("restaurantId"),
            avs.getString("restaurantName"));
    if (avs.hasAttribute("creationDate"))
      order.setCreationDate(new DateTime(avs.getLong("creationDate")));
    return order;
  }


  private List<OrderLineItem> toLineItems2(List<LinkedHashMap<String,
          Object>> lineItems) {
    return lineItems.stream().map(this::toLineItem2).collect(toList());
  }

  private OrderLineItem toLineItem2(LinkedHashMap<String, Object>
                                            attributeValue) {
    return new OrderLineItem((String) attributeValue.get("menuItemId"),
                             (String) attributeValue.get("menuItemName"),
                             new Money((String) attributeValue.get("price")),
                            ((BigDecimal) attributeValue.get("quantity")).intValue()
            );
  }

}
