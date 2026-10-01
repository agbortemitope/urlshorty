# urlshorty

A URL shortening service: a REST API that turns long URLs into short codes, resolves them again,
updates and deletes them, and reports how many times each code has been used.

Built with **Java 17** and **Spring Boot 3.3.5**, backed by an in-memory **H2** database by default
and ready for **MySQL**.

---

## Contents

- [Quick start](#quick-start)
- [API reference](#api-reference)
- [Configuration](#configuration)
- [Project structure](#project-structure)
- [Testing](#testing)
- [Extra tools](#extra-tools)
- [Documentation](#documentation)
- [Known limitations](#known-limitations)

---

## Quick start

### Requirements

- **JDK 17 or newer.** Nothing else: the build tool comes with the project.
- No Maven installation is needed. `mvnw` / `mvnw.cmd` download a private Maven copy on first use.

> **Check your Java version before you start.** Run `java -version`. If it prints `1.8` or anything
> below 17, the application will not start. Point the build at a newer JDK by setting `JAVA_HOME` to
> it, for example:
>
> ```powershell
> $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
> ```
>
> The `mvnw` scripts use `JAVA_HOME` when it is set and fall back to whatever `java` is on the
> `PATH` otherwise.

### Run the API

```powershell
# Windows (PowerShell or cmd)
.\mvnw.cmd spring-boot:run
```

```bash
# macOS / Linux
./mvnw spring-boot:run
```

The API starts on **http://localhost:8080** and creates its database automatically. Nothing to
install, nothing to configure.

### Try it in ten seconds

```bash
curl -X POST http://localhost:8080/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.example.com/some/long/url"}'
```

```json
{
  "id": "1",
  "url": "https://www.example.com/some/long/url",
  "shortCode": "lvF7LRu",
  "createdAt": "2026-10-01T10:53:42.118Z",
  "updatedAt": "2026-10-01T10:53:42.118Z"
}
```

Then read it back with `curl http://localhost:8080/shorten/lvF7LRu`.

### Run the whole life cycle as a script

With the application running in one terminal:

```powershell
.\scripts\smoke-test.ps1
```

It creates a short URL, resolves it three times, checks the statistics, updates it, provokes a
validation error and a 404, deletes it, and confirms it is gone. Every line prints PASS or FAIL and
the script exits with code 1 if anything is wrong. Requires PowerShell 7+.

---

## API reference

Base URL: `http://localhost:8080`. All request and response bodies are JSON.

| # | Method | Path | Success | Failure |
|---|--------|------|---------|---------|
| 1 | `POST` | `/shorten` | `201 Created` + record | `400` validation error |
| 2 | `GET` | `/shorten/{shortCode}` | `200 OK` + record | `404` unknown code |
| 3 | `PUT` | `/shorten/{shortCode}` | `200 OK` + updated record | `400` / `404` |
| 4 | `DELETE` | `/shorten/{shortCode}` | `204 No Content` | `404` unknown code |
| 5 | `GET` | `/shorten/{shortCode}/stats` | `200 OK` + record + `accessCount` | `404` |

A successful response looks like this:

```json
{
  "id": "1",
  "url": "https://www.example.com/some/long/url",
  "shortCode": "lvF7LRu",
  "createdAt": "2026-10-01T10:53:42.118Z",
  "updatedAt": "2026-10-01T10:53:42.118Z"
}
```

The statistics endpoint returns the same fields plus `accessCount`:

```json
{
  "id": "1",
  "url": "https://www.example.com/some/long/url",
  "shortCode": "lvF7LRu",
  "createdAt": "2026-10-01T10:53:42.118Z",
  "updatedAt": "2026-10-01T10:53:42.118Z",
  "accessCount": 3
}
```

Errors always use one shape, so a client needs a single parser:

```json
{
  "timestamp": "2026-10-01T10:53:42.201Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/shorten",
  "fieldErrors": {
    "url": "must be an absolute http or https URL, for example https://example.com/page"
  }
}
```

### 1. Create a short URL

`POST /shorten`

| Field | Type | Rules |
|-------|------|-------|
| `url` | string | required, at most 2048 characters, absolute `http` or `https` URL |

```bash
curl -i -X POST http://localhost:8080/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.example.com/some/long/url"}'
```

```powershell
Invoke-RestMethod -Method POST http://localhost:8080/shorten `
  -ContentType "application/json" `
  -Body '{"url":"https://www.example.com/some/long/url"}'
```

Returns `201 Created`, the new record in the body, and a `Location` header such as
`/shorten/lvF7LRu`. The short code is generated randomly; you cannot choose it.

### 2. Retrieve the original URL

`GET /shorten/{shortCode}`

```bash
curl http://localhost:8080/shorten/lvF7LRu
```

Returns `200 OK` with the record, or `404 Not Found` when the code does not exist. Each successful
call increases the access counter by one.

### 3. Update the original URL

`PUT /shorten/{shortCode}` - same body rules as creating.

```bash
curl -i -X PUT http://localhost:8080/shorten/lvF7LRu \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.example.com/some/updated/url"}'
```

Returns `200 OK` with the updated record. `createdAt` never changes and `updatedAt` moves to the
moment of the update. The access counter is not reset.

### 4. Delete a short URL

`DELETE /shorten/{shortCode}`

```bash
curl -i -X DELETE http://localhost:8080/shorten/lvF7LRu
```

Returns `204 No Content` with an empty body, or `404 Not Found`.

### 5. Statistics

`GET /shorten/{shortCode}/stats`

```bash
curl http://localhost:8080/shorten/lvF7LRu/stats
```

Returns `200 OK` with the record plus `accessCount`, or `404 Not Found`.

### A note on redirects

The specification keeps the redirect out of the API: `GET /shorten/{shortCode}` answers with JSON and
the *frontend* is expected to read `url` from that JSON and send the browser on with a 301. This
project implements the API half exactly as described. See
[docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md) for how a frontend would sit in front of it.

---

## Configuration

### Default: in-memory H2

No configuration at all. The database lives in memory, is created on startup and disappears when the
process stops. Perfect for development and for a reviewer who just wants to run the thing.

### Optional: MySQL

Create a database, then start the application with the `mysql` profile:

```sql
CREATE DATABASE urlshorty CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mysql"
```

Or run the packaged jar:

```powershell
java -jar target/urlshorty-1.0.0.jar --spring.profiles.active=mysql
```

Connection details are read from environment variables, so no password is ever stored in the
repository:

| Variable | Default |
|----------|---------|
| `DB_URL` | `jdbc:mysql://localhost:3306/urlshorty?...` |
| `DB_USER` | `urlshorty` |
| `DB_PASSWORD` | `urlshorty` |

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/urlshorty"
$env:DB_USER = "root"
$env:DB_PASSWORD = "secret"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mysql"
```

The equivalent `CREATE TABLE` statement is in [docs/schema.sql](docs/schema.sql) for reference; the
application builds the schema itself.

### Other settings

| Setting | Where | Default |
|---------|-------|---------|
| Server port | `server.port` in `application.yml` | `8080` |
| Short code length | `ShortCodeGenerator.CODE_LENGTH` | `7` |
| Database schema handling | `spring.jpa.hibernate.ddl-auto` | `update` |

To use a different port without editing anything:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=9090"
```

---

## Project structure

```
urlshorty/
├── pom.xml                          Maven build: dependencies and plugins
├── mvnw, mvnw.cmd                   Maven wrapper - no local Maven needed
├── README.md                        this file
├── docs/
│   ├── PROJECT_GUIDE.md             how everything works, in depth
│   ├── QUESTIONS_AND_ANSWERS.md     likely review questions with answers
│   └── schema.sql                   database schema for reference
├── scripts/
│   └── smoke-test.ps1               end-to-end check against a running server
└── src/
    ├── main/
    │   ├── java/com/urlshorty/
    │   │   ├── UrlShortyApplication.java      starts Spring Boot
    │   │   ├── config/JacksonConfig.java      one timestamp format everywhere
    │   │   ├── controller/UrlController.java  the five REST endpoints
    │   │   ├── dto/                           request and response bodies
    │   │   ├── entity/ShortUrl.java           the database row
    │   │   ├── exception/                     not-found error and the global handler
    │   │   ├── repository/ShortUrlRepository.java  database access
    │   │   ├── service/UrlShortenerService.java    business rules
    │   │   ├── util/ShortCodeGenerator.java   random Base62 codes
    │   │   └── validation/                    the URL constraint
    │   └── resources/
    │       ├── application.yml                default configuration (H2)
    │       └── application-mysql.yml          MySQL profile
    └── test/
        ├── java/com/urlshorty/
        │   ├── ShortenApiIntegrationTest.java  19 end-to-end HTTP tests
        │   ├── service/UrlShortenerServiceTest.java  6 business-rule tests
        │   └── util/ShortCodeGeneratorTest.java      3 generator tests
        └── resources/application.yml         test database settings
```

---

## Testing

```powershell
.\mvnw.cmd test
```

**28 tests, all passing.** They are split by how much of the application they exercise:

| Suite | Tests | What it proves |
|-------|-------|----------------|
| `ShortenApiIntegrationTest` | 19 | The full stack over HTTP: every endpoint, every status code, validation failures, unknown codes, and that lookups are counted |
| `UrlShortenerServiceTest` | 6 | Business rules with a mocked database, including retrying when a generated code is already taken |
| `ShortCodeGeneratorTest` | 3 | Codes have the right length, use only Base62 characters, and do not repeat over 10,000 draws |

Tests use their own in-memory database, so running them never touches your data.

---

## Extra tools

Two extras come with the application and are handy when demonstrating it:

| Tool | URL | What it is |
|------|-----|------------|
| Swagger UI | http://localhost:8080/swagger-ui.html | Click-through documentation: try any endpoint from the browser |
| OpenAPI JSON | http://localhost:8080/v3/api-docs | The same description as a machine-readable document |
| H2 console | http://localhost:8080/h2-console | Browse the database - JDBC URL `jdbc:h2:mem:urlshorty`, user `sa`, empty password |

---

## Documentation

- **[docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md)** - the full walkthrough: architecture, request
  life cycle, data model, the short-code algorithm, validation, error handling, the test strategy,
  and every design decision with its reasoning.
- **[docs/QUESTIONS_AND_ANSWERS.md](docs/QUESTIONS_AND_ANSWERS.md)** - prepared answers to the
  questions this project tends to attract, plus a glossary of the terms used in the code.

---

## Known limitations

Honest list, all of them deliberate choices for a project of this scope:

- **No authentication.** Anyone can create, update or delete short URLs. The specification
  explicitly says authentication is out of scope.
- **Nothing expires.** Short codes live until they are deleted.
- **Access counts are totals.** The API counts how often a code was resolved, not when or from
  where. There is no per-day breakdown and no referrer or user-agent data.
- **The database is the only storage.** Scaling out to several instances works, because every
  instance talks to the same database, but the configuration is not clustered or replicated.
- **`ddl-auto` builds the schema.** Convenient for this project; a production deployment would use
  a migration tool such as Flyway so schema changes are versioned and reviewable.
- **H2 forgets everything on restart** (in the default profile). Use the `mysql` profile to keep
  data.
