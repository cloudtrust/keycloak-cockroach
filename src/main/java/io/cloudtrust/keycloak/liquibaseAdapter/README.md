#Liquiase adaper

This section of a code contains a tool to transform the liquibase schema creation scripts in keycloak to be compatible with Cockroachdb. Most of this work should be automatic, but in case this tool cannot perform a suitable transformation, it must report the file, line and problem.

Currently setting the path of the files to transform is done in the code, but at a later date the tool must be callable from the command line.