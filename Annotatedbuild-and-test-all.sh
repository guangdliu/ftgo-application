#! /bin/bash -e

KEEP_RUNNING=
ASSEMBLE_ONLY=
DATABASE_SERVICES="dynamodblocal mysql dynamodblocal-init"

if [ -z "$DOCKER_COMPOSE" ] ; then
    DOCKER_COMPOSE=docker-compose
fi

while [ ! -z "$*" ] ; do
  case $1 in
    "--keep-running" )
      KEEP_RUNNING=yes
      ;;
    "--assemble-only" )
      ASSEMBLE_ONLY=yes
      ;;
    "--help" )
      echo ./build-and-test-all.sh --keep-running --assemble-only
      exit 0
      ;;
  esac
  shift
done

echo KEEP_RUNNING=$KEEP_RUNNING

. ./set-env.sh

# TODO Temporarily

/// buildContracts are defined at build.gradle
./gradlew buildContracts

./gradlew testClasses

${DOCKER_COMPOSE?} down --remove-orphans -v

///Start dynamodblocal mysql dynamodblocal-init 
///but when we create databases in mysql
///check mysql folder's Dockerfile.
///It invokes common-schema.sql, compile-schema-per-service.sql and template and compile-schema-per-service.sh
/***
note that  common-schema.sql, compile-schema-per-service.sql and template create multiple databases in the same
mysql server. 

common-schema creates a database called eventuate. 
eventuate just has one table: cdc_monitoring.
eventuate db is used by all different services. 

compile-schema-per-service.sql creates several databases:

ftgo_accounting_service ftgo_consumer_service ftgo_order_service ftgo_kitchen_service ftgo_restaurant_service

Each data base has its own user:
ftgo_accounting_service_user ftgo_consumer_service_user ftgo_order_service_user ftgo_kitchen_service_user ftgo_restaurant_service_user

Each of the db above is populated by template (this means all dbs have the same tables). These tables are:

events entities snapshots message received_messages offset_store saga_instance_participants saga_instance saga_lock_table saga_stash_table

***/

/// DATABASE_SERVICES = "dynamodblocal mysql dynamodblocal-init" 
/// note that  "dynamodblocal mysql dynamodblocal-init" are names in docker-compose.yml
/// and also folder names.
///docker-compose.yml goes to related lines of these name, and then go to the corresponding foler to build

///docker-compose goes to these folders and check their Dockerfile
${DOCKER_COMPOSE?} up -d --build ${DATABASE_SERVICES?}

/// waitForMysql is a gradle task and is created in WaitForMySqlPugin.java and WaitForMySql.java 
./gradlew waitForMySql

echo mysql is started

# Test ./mysql-cli.sh

///mysql is started in a docker container. run this sql command on mysql server in the docker.
/// we have a lot of dbs: eventuate ftgo_accounting_service ftgo_consumer_service ftgo_order_service ftgo_kitchen_service ftgo_restaurant_service
echo 'show databases;' | ./mysql-cli.sh -i
///Check docker-compose.yml and it has a cdc-service
${DOCKER_COMPOSE?} up -d --build cdc-service

if [ -z "$ASSEMBLE_ONLY" ] ; then

  ./gradlew -x :ftgo-end-to-end-tests:test $* build

  ${DOCKER_COMPOSE?} build

  ./gradlew $* integrationTest

  # Component tests need to use the per-service database schema

  ./gradlew :ftgo-order-service:cleanComponentTest :ftgo-order-service:componentTest

  # Reset the DB/messages

  ${DOCKER_COMPOSE?} down --remove-orphans -v

  ${DOCKER_COMPOSE?} up -d ${DATABASE_SERVICES?}

  ./gradlew waitForMySql

  echo mysql is started

  ${DOCKER_COMPOSE?} up -d


else

  ./gradlew $* assemble
  
/***

Builds, (re)creates, starts, and attaches to containers for a service.

Unless they are already running, this command also starts any linked services.

The docker-compose up command aggregates the output of each container 
(essentially running docker-compose logs -f). When the command exits, all containers are stopped. 
Running docker-compose up -d starts the containers in the background and leaves them running.

If there are existing containers for a service, and the service¡¯s configuration or image was changed after the container¡¯s creation, 
docker-compose up picks up the changes by stopping and recreating the containers (preserving mounted volumes). 
To prevent Compose from picking up changes, use the --no-recreate flag.

If you want to force Compose to stop and recreate all containers, use the --force-recreate flag.

If the process encounters an error, the exit code for this command is 
  
***/
///  --build Build images before starting containers. 
///  -d, Detached mode: Run containers in the background,  print new container names.
  ${DOCKER_COMPOSE?} up -d --build ${DATABASE_SERVICES?}

  ./gradlew waitForMySql

  echo mysql is started

  ${DOCKER_COMPOSE?} up -d --build

fi

./wait-for-services.sh

./run-end-to-end-tests.sh


./run-graphql-api-gateway-tests.sh

if [ -z "$KEEP_RUNNING" ] ; then
  ${DOCKER_COMPOSE?} down --remove-orphans -v
fi
