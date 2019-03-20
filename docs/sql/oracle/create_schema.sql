--
--  Create sequences.
--
CREATE SEQUENCE "PA_APPLICATION_SEQ" MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER NOCYCLE;
CREATE SEQUENCE "PA_APPLICATION_VERSION_SEQ" MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER NOCYCLE;
CREATE SEQUENCE "PA_MASTER_KEYPAIR_SEQ" MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER NOCYCLE;
CREATE SEQUENCE "PA_SIGNATURE_AUDIT_SEQ" MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER NOCYCLE;
CREATE SEQUENCE "PA_ACTIVATION_HISTORY_SEQ" MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER NOCYCLE;
CREATE SEQUENCE "PA_RECOVERY_CODE_SEQ" MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER NOCYCLE;
CREATE SEQUENCE "PA_RECOVERY_PUK_SEQ" MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER NOCYCLE;

--
--  DDL for Table PA_ACTIVATION
--
CREATE TABLE "PA_ACTIVATION"
(
    "ACTIVATION_ID"                 VARCHAR2(37 CHAR) NOT NULL PRIMARY KEY,
    "APPLICATION_ID"                NUMBER(19,0) NOT NULL,
    "USER_ID"                       VARCHAR2(255 CHAR) NOT NULL,
    "ACTIVATION_NAME"               VARCHAR2(255 CHAR),
    "ACTIVATION_CODE"               VARCHAR2(255 CHAR),
    "ACTIVATION_STATUS"             NUMBER(10,0) NOT NULL,
    "BLOCKED_REASON"                VARCHAR2(255 CHAR),
    "COUNTER"                       NUMBER(19,0) NOT NULL,
    "CTR_DATA"                      VARCHAR2(255 CHAR),
    "DEVICE_PUBLIC_KEY_BASE64"      VARCHAR2(255 CHAR),
    "EXTRAS"                        VARCHAR2(255 CHAR),
    "FAILED_ATTEMPTS"               NUMBER(19,0) NOT NULL,
    "MAX_FAILED_ATTEMPTS"           NUMBER(19,0) DEFAULT 5 NOT NULL,
    "SERVER_PRIVATE_KEY_BASE64"     VARCHAR2(255 CHAR) NOT NULL,
    "SERVER_PRIVATE_KEY_ENCRYPTION" NUMBER(10,0) DEFAULT 0 NOT NULL,
    "SERVER_PUBLIC_KEY_BASE64"      VARCHAR2(255 CHAR) NOT NULL,
    "TIMESTAMP_ACTIVATION_EXPIRE"   TIMESTAMP (6) NOT NULL,
    "TIMESTAMP_CREATED"             TIMESTAMP (6) NOT NULL,
    "TIMESTAMP_LAST_USED"           TIMESTAMP (6) NOT NULL,
    "TIMESTAMP_LAST_CHANGE"         TIMESTAMP (6),
    "MASTER_KEYPAIR_ID"             NUMBER(19,0),
    "VERSION"                       NUMBER(2,0) DEFAULT 2
);

--
--  DDL for Table PA_APPLICATION
--
CREATE TABLE "PA_APPLICATION"
(
    "ID"   NUMBER(19,0) NOT NULL PRIMARY KEY,
    "NAME" VARCHAR2(255 CHAR)
);


--
--  DDL for Table PA_APPLICATION_VERSION
--
CREATE TABLE "PA_APPLICATION_VERSION"
(
    "ID"                 NUMBER(19,0) NOT NULL PRIMARY KEY,
    "APPLICATION_ID"     NUMBER(19,0) NOT NULL,
    "APPLICATION_KEY"    VARCHAR2(255 CHAR),
    "APPLICATION_SECRET" VARCHAR2(255 CHAR),
    "NAME"               VARCHAR2(255 CHAR),
    "SUPPORTED"          NUMBER(1,0)
);

--
--  DDL for Table PA_MASTER_KEYPAIR
--
CREATE TABLE "PA_MASTER_KEYPAIR"
(
    "ID"                        NUMBER(19,0) NOT NULL PRIMARY KEY,
    "APPLICATION_ID"            NUMBER(19,0) NOT NULL,
    "MASTER_KEY_PRIVATE_BASE64" VARCHAR2(255 CHAR) NOT NULL,
    "MASTER_KEY_PUBLIC_BASE64"  VARCHAR2(255 CHAR) NOT NULL,
    "NAME"                      VARCHAR2(255 CHAR),
    "TIMESTAMP_CREATED"         TIMESTAMP (6) NOT NULL
);

--
--  DDL for Table PA_SIGNATURE_AUDIT
--
CREATE TABLE "PA_SIGNATURE_AUDIT"
(
    "ID"                  NUMBER(19,0) NOT NULL PRIMARY KEY,
    "ACTIVATION_ID"       VARCHAR2(37 CHAR) NOT NULL,
    "ACTIVATION_COUNTER"  NUMBER(19,0) NOT NULL,
    "ACTIVATION_CTR_DATA" VARCHAR2(255 CHAR),
    "ACTIVATION_STATUS"   NUMBER(10,0),
    "ADDITIONAL_INFO"     VARCHAR2(255 CHAR),
    "DATA_BASE64"         CLOB,
    "NOTE"                VARCHAR2(255 CHAR),
    "SIGNATURE_TYPE"      VARCHAR2(255 CHAR) NOT NULL,
    "SIGNATURE"           VARCHAR2(255 CHAR) NOT NULL,
    "TIMESTAMP_CREATED"   TIMESTAMP (6) NOT NULL,
    "VALID"               NUMBER(1,0) DEFAULT 0 NOT NULL,
    "VERSION"             NUMBER(2,0) DEFAULT 2
);

--
--  DDL for Table PA_INTEGRATION
--
CREATE TABLE "PA_INTEGRATION"
(
    "ID"                 VARCHAR2(37 CHAR) NOT NULL PRIMARY KEY,
    "NAME"               VARCHAR2(255 CHAR),
    "CLIENT_TOKEN"       VARCHAR2(37 CHAR) NOT NULL,
    "CLIENT_SECRET"      VARCHAR2(37 CHAR) NOT NULL
);

--
--  DDL for Table PA_APPLICATION_CALLBACK
--
CREATE TABLE "PA_APPLICATION_CALLBACK"
(
    "ID"                 VARCHAR2(37 CHAR) NOT NULL PRIMARY KEY,
    "APPLICATION_ID"     NUMBER(19,0) NOT NULL,
    "NAME"               VARCHAR2(255 CHAR),
    "CALLBACK_URL"       VARCHAR2(1024 CHAR)
);

--
-- DDL for Table PA_TOKEN
--

CREATE TABLE "PA_TOKEN"
(
    "TOKEN_ID"           VARCHAR2(37 CHAR) NOT NULL PRIMARY KEY,
    "TOKEN_SECRET"       VARCHAR2(255 CHAR) NOT NULL,
    "ACTIVATION_ID"      VARCHAR2(255 CHAR) NOT NULL,
    "SIGNATURE_TYPE"     VARCHAR2(255 CHAR) NOT NULL,
    "TIMESTAMP_CREATED"  TIMESTAMP (6) NOT NULL
);

--
--  DDL for Table PA_ACTIVATION_HISTORY
--
CREATE TABLE "PA_ACTIVATION_HISTORY"
(
    "ID"                 NUMBER(19,0) NOT NULL PRIMARY KEY,
    "ACTIVATION_ID"      VARCHAR2(37 CHAR) NOT NULL,
    "ACTIVATION_STATUS"  NUMBER(10,0),
    "BLOCKED_REASON"     VARCHAR2(255 CHAR),
    "EXTERNAL_USER_ID"   VARCHAR2(255 CHAR),
    "TIMESTAMP_CREATED"  TIMESTAMP (6) NOT NULL
);

--
-- DDL for Table PA_RECOVERY_CODE
--

CREATE TABLE "PA_RECOVERY_CODE" (
  "ID"                    NUMBER(19,0) NOT NULL PRIMARY KEY,
  "RECOVERY_CODE"         VARCHAR2(23 CHAR) NOT NULL,
  "APPLICATION_ID"        NUMBER(19,0) NOT NULL,
  "USER_ID"               VARCHAR2(255 CHAR) NOT NULL,
  "ACTIVATION_ID"         VARCHAR2(37 CHAR),
  "STATUS"                NUMBER(10,0) NOT NULL,
  "FAILED_ATTEMPTS"       NUMBER(19,0) DEFAULT 0 NOT NULL,
  "MAX_FAILED_ATTEMPTS"   NUMBER(19,0) DEFAULT 10 NOT NULL,
  "TIMESTAMP_CREATED"     TIMESTAMP (6) NOT NULL,
  "TIMESTAMP_LAST_USED"   TIMESTAMP (6),
  "TIMESTAMP_LAST_CHANGE" TIMESTAMP (6)
);

--
-- DDL for Table PA_RECOVERY_PUK
--

CREATE TABLE "PA_RECOVERY_PUK" (
  "ID"                    NUMBER(19,0) NOT NULL PRIMARY KEY,
  "RECOVERY_CODE_ID"      NUMBER(19,0) NOT NULL,
  "PUK"                   VARCHAR2(60 CHAR),
  "PUK_INDEX"             NUMBER(19,0) NOT NULL,
  "STATUS"                NUMBER(10,0) NOT NULL,
  "TIMESTAMP_LAST_CHANGE" TIMESTAMP (6)
);

--
--  Ref Constraints for Table PA_ACTIVATION
--
ALTER TABLE "PA_ACTIVATION" ADD CONSTRAINT "ACTIVATION_KEYPAIR_FK" FOREIGN KEY ("MASTER_KEYPAIR_ID") REFERENCES "PA_MASTER_KEYPAIR" ("ID") ENABLE;
ALTER TABLE "PA_ACTIVATION" ADD CONSTRAINT "ACTIVATION_APPLICATION_FK" FOREIGN KEY ("APPLICATION_ID") REFERENCES "PA_APPLICATION" ("ID") ENABLE;

--
--  Ref Constraints for Table PA_APPLICATION_VERSION
--
ALTER TABLE "PA_APPLICATION_VERSION" ADD CONSTRAINT "VERSION_APPLICATION_FK" FOREIGN KEY ("APPLICATION_ID") REFERENCES "PA_APPLICATION" ("ID") ENABLE;

--
--  Ref Constraints for Table PA_MASTER_KEYPAIR
--
ALTER TABLE "PA_MASTER_KEYPAIR" ADD CONSTRAINT "KEYPAIR_APPLICATION_FK" FOREIGN KEY ("APPLICATION_ID") REFERENCES "PA_APPLICATION" ("ID") ENABLE;

--
--  Ref Constraints for Table PA_SIGNATURE_AUDIT
--
ALTER TABLE "PA_SIGNATURE_AUDIT" ADD CONSTRAINT "AUDIT_ACTIVATION_FK" FOREIGN KEY ("ACTIVATION_ID") REFERENCES "PA_ACTIVATION" ("ACTIVATION_ID") ENABLE;

--
--  Ref Constraints for Table PA_APPLICATION_CALLBACK
--
ALTER TABLE "PA_APPLICATION_CALLBACK" ADD CONSTRAINT "CALLBACK_APPLICATION_FK" FOREIGN KEY ("APPLICATION_ID") REFERENCES "PA_APPLICATION" ("ID") ENABLE;

--
--  Ref Constraints for Table PA_TOKEN
--
ALTER TABLE "PA_TOKEN" ADD CONSTRAINT "ACTIVATION_TOKEN_FK" FOREIGN KEY ("ACTIVATION_ID") REFERENCES "PA_ACTIVATION" ("ACTIVATION_ID") ENABLE;

--
--  Ref Constraints for Table PA_ACTIVATION_HISTORY
--
ALTER TABLE "PA_ACTIVATION_HISTORY" ADD CONSTRAINT "HISTORY_ACTIVATION_FK" FOREIGN KEY ("ACTIVATION_ID") REFERENCES "PA_ACTIVATION" ("ACTIVATION_ID") ENABLE;

--
--  Ref Constraints for Table PA_RECOVERY_CODE
--
ALTER TABLE "PA_RECOVERY_CODE" ADD CONSTRAINT "RECOVERY_CODE_APPLICATION_FK" FOREIGN KEY ("APPLICATION_ID") REFERENCES "PA_APPLICATION" ("ID") ENABLE;
ALTER TABLE "PA_RECOVERY_CODE" ADD CONSTRAINT "RECOVERY_CODE_ACTIVATION_FK" FOREIGN KEY ("ACTIVATION_ID") REFERENCES "PA_ACTIVATION" ("ACTIVATION_ID") ENABLE;

--
--  Ref Constraints for Table PA_RECOVERY_PUK
--
ALTER TABLE "PA_RECOVERY_PUK" ADD CONSTRAINT "RECOVERY_PUK_CODE_FK" FOREIGN KEY ("RECOVERY_CODE_ID") REFERENCES "PA_RECOVERY_CODE" ("ID") ENABLE;

---
--- Indexes for better performance. Oracle does not create indexes on foreign key automatically.
---

CREATE INDEX PA_ACTIVATION_APPLICATION ON PA_ACTIVATION(APPLICATION_ID);

CREATE INDEX PA_ACTIVATION_KEYPAIR ON PA_ACTIVATION(MASTER_KEYPAIR_ID);

CREATE INDEX PA_ACTIVATION_CODE ON PA_ACTIVATION(ACTIVATION_CODE);

CREATE INDEX PA_ACTIVATION_USER_ID ON PA_ACTIVATION(USER_ID);

CREATE INDEX PA_ACTIVATION_HISTORY_ACT ON PA_ACTIVATION_HISTORY(ACTIVATION_ID);

CREATE INDEX PA_ACTIVATION_HISTORY_CREATED ON PA_ACTIVATION_HISTORY(TIMESTAMP_CREATED);

CREATE INDEX PA_APPLICATION_VERSION_APP ON PA_APPLICATION_VERSION(APPLICATION_ID);

CREATE INDEX PA_MASTER_KEYPAIR_APPLICATION ON PA_MASTER_KEYPAIR(APPLICATION_ID);

CREATE UNIQUE INDEX PA_APP_VERSION_APP_KEY ON PA_APPLICATION_VERSION(APPLICATION_KEY);

CREATE INDEX PA_APP_CALLBACK_APP ON PA_APPLICATION_CALLBACK(APPLICATION_ID);

CREATE UNIQUE INDEX PA_INTEGRATION_TOKEN ON PA_INTEGRATION(CLIENT_TOKEN);

CREATE INDEX PA_SIGNATURE_AUDIT_ACTIVATION ON PA_SIGNATURE_AUDIT(ACTIVATION_ID);

CREATE INDEX PA_SIGNATURE_AUDIT_CREATED ON PA_SIGNATURE_AUDIT(TIMESTAMP_CREATED);

CREATE INDEX PA_TOKEN_ACTIVATION ON PA_TOKEN(ACTIVATION_ID);

CREATE INDEX PA_RECOVERY_CODE ON PA_RECOVERY_CODE(RECOVERY_CODE);

CREATE INDEX PA_RECOVERY_CODE_APP ON PA_RECOVERY_CODE(APPLICATION_ID);

CREATE INDEX PA_RECOVERY_CODE_USER ON PA_RECOVERY_CODE(USER_ID);

CREATE INDEX PA_RECOVERY_CODE_ACT ON PA_RECOVERY_CODE(ACTIVATION_ID);

CREATE UNIQUE INDEX PA_RECOVERY_CODE_PUK ON PA_RECOVERY_PUK(RECOVERY_CODE_ID, PUK_INDEX);

CREATE INDEX PA_RECOVERY_PUK_CODE ON PA_RECOVERY_PUK(RECOVERY_CODE_ID);
