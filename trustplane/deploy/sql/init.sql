-- Creates the service databases (executed as POSTGRES_USER).
CREATE DATABASE idp;
CREATE DATABASE rbac;
CREATE DATABASE gateway;
CREATE DATABASE comms;   -- volumes created before comms existed: the service creates it at boot
