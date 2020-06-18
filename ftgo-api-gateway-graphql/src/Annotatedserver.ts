import * as express from 'express'
import {Request} from 'express';

const {graphqlExpress} = require("apollo-server-express");
const bodyParser = require("body-parser");
const {schema} = require("./schema"); /// import the GraphQL schema in which the schema and resolvers are defined.
const {OrderServiceProxy} = require("./OrderServiceProxy")
const {ConsumerServiceProxy} = require("./ConsumerServiceProxy")
const {RestaurantServiceProxy} = require("./RestaurantServiceProxy")


const app = express();

function makeContextWithDependencies(req: Request) {///8.10 4. Inject Repositories into the context so they're available to resolvers. 
    const orderServiceProxy = new OrderServiceProxy({baseUrl: process.env.ORDER_HISTORY_SERVICE_URL || "http://localhost:8080"});
    const consumerServiceProxy = new ConsumerServiceProxy({baseUrl: process.env.CONSUMER_SERVICE_URL || "http://localhost:8080"});
    const restaurantServiceProxy = new RestaurantServiceProxy({baseUrl: process.env.RESTAURANT_SERVICE_URL || "http://localhost:8080"});
    return {orderServiceProxy, consumerServiceProxy, restaurantServiceProxy};
}

function makeGraphQLHandler() {/// 5. Make an express request handler that executes GraphQL queries against the execuable schema.
    return graphqlExpress((req: Request) => {
        console.log("req=", req.url);
        return {schema: schema, context: makeContextWithDependencies(req)}
    });
}

app.post('/graphql', bodyParser.json(), makeGraphQLHandler()); /// 6 Route POST /graphql and GET /graghql endpoints to the GraphQL server.

app.get('/graphql', makeGraphQLHandler());

exports.app = app;
