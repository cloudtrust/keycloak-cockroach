# keycloak-cockroach


This repository contains the changes needed to make keycloak work with cockroackdb.

The _liquidbase-converter_ is a tool to automatically convert keycloak liquidbase scripts to make it compliant with CockroachDB.

Some of the adaption needed for liquidbase scripts still need to be performed manually, the final compatible liquidbase scripts are stored in _liquidbase_ directory.



## Current status

The liquidbase scripts have been adapted until version 3.4.3, the result schema is the same as the one for Postgres.

Some unittests fails with CockraochDB, we are analyzing it.




