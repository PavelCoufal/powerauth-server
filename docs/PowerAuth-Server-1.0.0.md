# Migration from 0.24.0 to 1.0.0

This guide contains instructions for migration from PowerAuth Server version `0.24.0` to version `1.0.0`.

## Database Changes

Following DB changes occurred between version 0.24.0 and 1.0.0:
- Table `pa_activation` - added column `flags`.
- Table `pa_application` - added column `roles`.
- Table `pa_application_callback` - added column `attributes`.

Migration script for Oracle:

```sql
ALTER TABLE "PA_ACTIVATION" ADD "FLAGS" VARCHAR2(255 CHAR);
ALTER TABLE "PA_APPLICATION" ADD "ROLES" VARCHAR2(255 CHAR);
ALTER TABLE "PA_APPLICATION_CALLBACK" ADD "ATTRIBUTES" VARCHAR2(1024 CHAR);
```

Migration script for MySQL:

```sql
ALTER TABLE `pa_activation` ADD `flags` varchar(255);
ALTER TABLE `pa_application` ADD `roles` varchar(255);
ALTER TABLE `pa_application_callback` `attributes` text NOT NULL;
```

Migration script for PostgreSQL:

```sql
ALTER TABLE "pa_activation" ADD "flags" VARCHAR(255);
ALTER TABLE "pa_application" ADD "roles" VARCHAR(255);
ALTER TABLE "pa_application_callback" ADD "attributes" VARCHAR(1024);
```

## New REST Client and SOAP Client Updates

We introduced a new REST client in release 1.0.0 and recommend migrating to the REST client in case you use the SOAP client,
the provided functionality is identical.

The SOAP clients for Spring and Java EE are still available, however these clients are marked as deprecated, and they will 
be removed in a future release.

Marshaller context path setting have been updated due to the migration of client model classes and due to company name change to Wultra. 

Original context path setting:

```java
marshaller.setContextPath("io.getlime.powerauth.soap.v3");
```

New context path setting:
```java
marshaller.setContextPath("com.wultra.security.powerauth.client.v3");
```

The version 2 context path package has changed the same way, so you will need to update the version 2 path in case you still use the version 2 interface, too.

In your client projects, use the new `com.wultra.security.powerauth.client` packages for the client model classes.

For more information about the new REST client, see [the REST client documentation](./Configuring-REST-Client-for-Spring.md)
