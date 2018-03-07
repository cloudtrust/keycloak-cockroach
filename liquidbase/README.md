# Liquidbase scripts for Cockroach



## Install Cockroach
	wget -qO- https://binaries.cockroachdb.com/cockroach-v1.1.4.linux-amd64.tgz | tar xvz
	sudo cp -i cockroach-v1.1.4.linux-amd64/cockroach /usr/local/bin
	rm -R cockroach-v1.1.4.linux-amd64/
		
## Run, interact with and monitor Cockroach

- First instance:
	cd ~
	cockroach start --host=localhost --insecure --store=cockroach-node1

- Second instance:
	cd ~
	cockroach start --host=localhost --insecure --store=cockroach-node2 --port=26258 --http-port=8081 --join=localhost:26257

- To run some SQL :
	cockroach sql --insecure
	
- To intialized everything for Keycloak, run the following SQL statements :
	CREATE DATABASE keycloak;
	CREATE USER keycloak WITH PASSWORD 'keycloak';
	GRANT ALL ON DATABASE keycloak TO keycloak;
	
- To monitor the cockroack cluster : http://localhost:8080


## Keycloak adaptions

### Liquidbase
- Copy the cockroach version of liquidbase scripts in Keycloak.

### Java
-Acquire lock
Change the code of Keycloak, CustomLockService.acquireLock() must always returns true.

-Named queries
Since version 3.4.0: 
2 Named queries have been modified to improve performance. It now uses correlated subqueries, which is not currently supported by CockroachDB.
To fix it we came back to the previous version of the query.
PersistentClientSessionEntity:37

	@NamedQuery(name="deleteDetachedClientSessions", query="delete from PersistentClientSessionEntity sess where sess.userSessionId NOT IN (select u.userSessionId from PersistentUserSessionEntity u)")

PersistentUserSessionEntity:37

	@NamedQuery(name="deleteDetachedUserSessions", query="delete from PersistentUserSessionEntity sess where sess.userSessionId NOT IN (select c.userSessionId from PersistentClientSessionEntity c)")
