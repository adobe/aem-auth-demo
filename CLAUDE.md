# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Purpose

**aem-auth-demo** is a Docker-based AEM demonstration project showing SAML 2.0 and OIDC/OAuth2 authentication via Keycloak as the Identity Provider. It is an AEM archetype project targeting AEM Publish with automated certificate exchange and ACL-protected content.

## Quick Start

```bash
# 1. Create .env file
cp env.example .env
# Edit .env: set AEM_QUICKSTART=/path/to/cq-quickstart-*.jar and PUBLISH_URL=http://localhost:8085

# 2. Start all services
docker-compose up
```

- **AEM Publish**: http://localhost:4503
- **Keycloak Admin**: http://localhost:8081 (admin/admin)
- **Dispatcher**: http://localhost:8085
- **Test user**: test / test

## Build & Deploy

```bash
# Build all modules (no running AEM needed)
mvn clean install

# Build and deploy to running AEM publish (localhost:4503)
mvn clean install -PautoInstallSinglePackagePublish

# Build and deploy to AEM author (localhost:4502)
mvn clean install -PautoInstallSinglePackage

# Deploy only the OSGi bundle
mvn clean install -PautoInstallBundle

# Run unit tests only
mvn clean test

# Run a single test class
mvn test -Dtest=ErrorInterceptorFilterTest -pl core
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `core/` | OSGi bundle: Sling Models, Servlets, Filters, Services |
| `ui.apps/` | AEM components, clientlibs, templates under `/apps` |
| `ui.config/` | OSGi configurations (`.cfg.json` files) |
| `ui.content/` | Page content, site structure |
| `ui.frontend/` | Webpack/npm frontend build → clientlib generation |
| `all/` | Aggregator package that embeds all modules for deployment |
| `dispatcher/` | Apache Dispatcher virtual host and cache config |
| `keycloak/` | Keycloak realm import (`sling.json`) and H2 database |

## Authentication Architecture

Two protected paths, each using a different auth mechanism:

- **SAML**: `/content/aem-auth-demo/us/en/saml-authenticated`
  - Handler: `com.adobe.granite.auth.saml.SamlAuthenticationHandler`
  - Config: `ui.config/.../com.adobe.granite.auth.saml.SamlAuthenticationHandler~saml_local_keycloak.cfg.json`
  - SP Entity ID: `test-saml`, IdP identifier: `saml-idp`
  - Group attribute mapped from Keycloak: `groups`

- **OIDC**: `/content/aem-auth-demo/us/en/oauth2-authenticated`
  - Handler: `org.apache.sling.auth.oauth_client.impl.OidcAuthenticationHandler`
  - Config: `ui.config/.../org.apache.sling.auth.oauth_client.impl.OidcAuthenticationHandler~my-idp.cfg.json`
  - Authorization code flow with PKCE, scopes: `openid profile email`
  - IdP identifier: `oidc-idp`

Both flows sync external groups from Keycloak into Oak. ACLs are enforced via Repoinit with `ignoreMissingPrincipal`:
- SAML page requires group `offline_access;saml-idp`
- OIDC page requires group `test-group`

All OSGi configs live under `ui.config/src/main/content/jcr_root/apps/aem-auth-demo/osgiconfig/config.publish/`.

## SAML Certificate Setup

`saml_setup.sh` runs automatically inside the AEM container on startup (via `docker-entrypoint.sh`). It:
1. Generates a 4096-bit RSA key pair + self-signed X.509 cert for the AEM Service Provider
2. Retrieves the Keycloak IdP SAML descriptor to extract its signing certificate
3. Uploads the SP cert to Keycloak via its REST API
4. Imports the IdP cert into AEM's Global Truststore
5. Installs the SP private key in the `authentication-service` system user keystore (PKCS8, alias `aem-sp`)

Certificates are generated at runtime — nothing is stored in the repo.

## Environment Variables

All variables are defined in `.env` (copy from `env.example`):

| Variable | Default | Description |
|----------|---------|-------------|
| `AEM_QUICKSTART` | *(required)* | Path to AEM quickstart JAR |
| `PUBLISH_URL` | `http://localhost:8085` | Public base URL for authentication links. Used by `SiteConfigService` to build absolute links in the `authlinks` component. For RDE/Cloud set via `aio aem rde env set PUBLISH_URL https://publish-pXXXXX-eYYYYYY.adobeaemcloud.com` |
| `KEYCLOAK_URL` | `http://keycloak:8080` | Internal Keycloak URL (Docker network) |
| `KEYCLOAK_REALM` | `sling` | Realm name |
| `KEYCLOAK_SAML_CLIENT_ID` | `test-saml` | Keycloak SAML client ID |
| `OPENSSL_PASS` | `admin` | Password for generated keystore |
| `OIDC_BASE_URL` | `http://host.docker.internal:8081/realms/sling` | OIDC issuer URL |
| `OIDC_CLIENT_ID` | `oidc-test` | OIDC client ID |
| `OIDC_CLIENT_SECRET` | *(in env.example)* | OIDC client secret |

## Custom Java Components (core/)

- **`ErrorInterceptorFilter`** — Servlet filter that intercepts OAuth error query parameters before the response commits, buffering output to redirect to custom error pages. Registered at `sling.filter.scope=REQUEST`.
- **`GroupProvisionerServlet`** — Manages creation of external groups for Repoinit-based ACLs.
- **`SecretDataServlet`** — Demo protected endpoint; returns data only when caller is authenticated.
- **`GroupMemberCountServlet`** — Returns group membership counts for demo dashboards.
- **`AuthLinksModel`** — Sling Model providing SAML and OIDC login/logout link URLs to HTL templates.

## Docker Container Lifecycle

`docker-entrypoint.sh` sequence inside the AEM container:
1. Start AEM quickstart JAR (`java -jar`)
2. Poll Felix console until AEM is ready (up to 240 retries × 10s)
3. Execute `saml_setup.sh` (cert generation and exchange)
4. `mvn clean install -PautoInstallSinglePackagePublish` (deploys everything)
5. Restart AEM to activate configuration
6. Tail `error.log`

## Resetting State

```bash
# Stop and remove containers
docker-compose down

# Reset Keycloak H2 database (loses user sessions, reimports realm on next start)
rm -rf keycloak/h2 keycloak/transaction-logs
```
