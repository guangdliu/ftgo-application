const { makeExecutableSchema } = require("graphql-tools");

const fetch = require("node-fetch");

const gql = String.raw;

// Construct a schema, using GraphQL schema language
// TODO need order(orderId) which does API composition

/***
query {

	consumer(consumerId:1) {/// consumer is top level query, use resolveConsumer function to resolve it
		id
		firstName
		lastName
		orders { /// I guess this'll call orders query which expects an input of consumerId which is derived from consumerId
				/// orders is not a top level query, use resolveOrderConsumer
			orderId
			restaurant { /// not a top level query, resolveOrderRestaurant 
				id
				name
			}
			deliveryInfo {
				estimatedDeliveryTime
				name
			}
		
		}
		
	}

}


***/

const typeDefs = gql`
  type Query {///Define the queries that a client can execute
    orders(consumerId : Int!): [Order] /// Returns the orders for the specified consumer
    order(orderId : Int!): Order /// Return the specified order
    consumer(consumerId : Int!): Consumer /// Return the specified Consumer
  }

  type Mutation {
    createConsumer(c : ConsumerInfo) : Consumer
  }

  type Order {
    orderId: ID,
    consumerId : Int,
    consumer: Consumer
    restaurant: Restaurant
    deliveryInfo : DeliveryInfo
  }

  type Restaurant {
    id: ID
    name: String
  }

  type Consumer {
    id: ID
    firstName: String
    lastName: String
    orders: [Order] /// 3. A consumer has a list of orders
  }

  input ConsumerInfo {
    firstName: String
    lastName: String
  }
  
  type DeliveryInfo {
    status : DeliveryStatus
    estimatedDeliveryTime : Int
    assignedCourier :String
  }
  
  enum DeliveryStatus {
    PREPARING
    READY_FOR_PICKUP
    PICKED_UP
    DELIVERED
  }

`;

/***
A resolver function has 3 params:

Object
For a top level query field, such as resolveOrders, object is a root object
that's usually ingnored by the resolver function. 
For Non top level query, objec the the value returned by the resolver
for the parent object. For example, the resolver function for the Order.consumer
field is passed the value returned by the Order's resolver function.

***/


function resolveOrders(_, { consumerId }, context) {
  return context.orderServiceProxy.findOrders(consumerId);
}

function resolveConsumer(_, { consumerId }, context) {
  return context.consumerServiceProxy.findConsumer(consumerId);
}

function resolveOrder(_, { orderId }, context) {
  return context.orderServiceProxy.findOrder(orderId);
}

function resolveOrderConsumer({consumerId}, args, context) {
    return context.consumerServiceProxy.findConsumer(consumerId);
}

function resolveOrderRestaurant({restaurantId}, args, context) {
    return context.restaurantServiceProxy.findRestaurant(restaurantId);
}

function resolveOrderDeliveryInfo({orderId}, args, context) {
    return context.deliveryServiceProxy.findDeliveryForOrder(orderId);
}

function resolveConsumerOrders({id, orders}, args, context) {
  return orders || context.orderServiceProxy.findOrders(id)
}

function createConsumer(_, { c: {firstName, lastName} }, context) {
  return context.consumerServiceProxy.createConsumer(firstName, lastName);
}

const resolvers = {
  Query: {/// these are top level queries
    orders: resolveOrders, /// 8.8 1: the resolver for the order query
    consumer: resolveConsumer, ///8.8 2: the resolver for the consumer field of an Order
    order: resolveOrder
  },
  Mutation: {
    createConsumer: createConsumer
  },
  Order: {/// these are non-top-level query which can be invoked with the return value of resolveOrder
    consumer: resolveOrderConsumer,
    restaurant: resolveOrderRestaurant,
    deliveryInfo: resolveOrderDeliveryInfo
  },
  Consumer: {
    orders: resolveConsumerOrders
  }
};

const schema = makeExecutableSchema({ typeDefs, resolvers });


module.exports = { schema };
