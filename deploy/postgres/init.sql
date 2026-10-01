-- Each service owns its own database: the triage service never reads the API's tables directly.
CREATE DATABASE triage OWNER flakehunter;
