const fetch = require("node-fetch");
const DataLoader = require('dataloader');

/***
Listing 8.9 Using a DataLoader to optimize calls to Restaurant Service
***/
class RestaurantServiceProxy {
    constructor(options) {
        this.restaurantServiceUrl = `${options.baseUrl}/restaurants`;
        this.dataLoader = new DataLoader(restaurantIds => this.batchFindRestaurants(restaurantIds)); /// 1 Create a DataLoader, which uses batchFindRestaurants
        console.log("this.restaurantServiceUrl", this.restaurantServiceUrl);
    }

    findRestaurant(restaurantId) {
        return this.dataLoader.load(restaurantId); /// 2. 
    }

    findRestaurantInternal(restaurantId) {
        return fetch(`${this.restaurantServiceUrl}/${restaurantId}`)
            .then(response => {
                console.log("response=", response.status);
                if (response.status === 200) {
                    return response.json().then(body => {
                        console.log("response=", body);
                        return Object.assign({id: restaurantId, name: body.name.name}, body);
                    })
                } else
                    return Promise.reject(new Error("cannot found restaurant for id" + restaurantId))
            });
    }

    batchFindRestaurants(restaurantIds) {/// 3. load a batch of Restaurants 
        console.log("restaurantIds=", restaurantIds);
        return Promise.all(restaurantIds.map(k => this.findRestaurantInternal(k)));
    }

}

module.exports = {RestaurantServiceProxy};
