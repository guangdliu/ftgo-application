#! /bin/bash -e

/***
Once you¡¯ve written the YAML file, you can create or update the deployment by
using the kubectl apply command.

This command makes a request to the Kubernetes API server that results in the creation
of the deployment and the pods.
***/
kubectl apply -f <(cat deployment/kubernetes/stateful-services/*.yml)

./deployment/kubernetes/scripts/kubernetes-wait-for-ready-pods.sh ftgo-mysql-0 ftgo-kafka-0 ftgo-dynamodb-local-0 ftgo-zookeeper-0

kubectl apply -f <(cat deployment/kubernetes/cdc-services/*.yml)

/// This is the KEY! It scans all projects folder and find yml
/// for each individual service, e.g. ftgo-restaurant-service/src/deployment/kubernetes/ftgorestaurant-service.yml
kubectl apply -f <(cat */src/deployment/kubernetes/*.yml)
