# urlshorty - the complete guide

This document explains what was built, how it works, why it is built that way, and what to say when
somebody asks about it. It is written to be read top to bottom once, and then used as a reference.

If you only have five minutes, read **1. The brief**, **3. Architecture** and **12. Design
decisions**. Everything else is detail you can look up later.

---

## Contents

1. [The brief](#1-the-brief)
2. [What was built](#2-what-was-built)
3. [Architecture](#3-architecture)
4. [Request life cycle](#4-request-life-cycle)
5. [The data model](#5-the-data-model)
6. [Short codes](#6-short-codes)
7. [Input validation](#7-input-validation)
8. [Error handling](#8-error-handling)
9. [Counting access](#9-counting-access)
10. [Timestamps](#10-timestamps)
11. [Testing](#11-testing)
12. [Design decisions](#12-design-decisions)
13. [What was left out, and how you would add it](#13-what-was-left-out-and-how-you-would-add-it)
14. [Demonstrating it](#14-demonstrating-it)
15. [Code map](#15-code-map)

---

## 1. The brief

The task, from <https://roadmap.sh/projects/url-shortening-service>, is a REST API that shortens long
URLs. Verbatim requirements:

- Create a short URL from a long one.
- Retrieve the original URL from a short code.
- Update an existing short URL.
- Delete an existing short URL.
- Report statistics - how often a short URL was accessed.

Two details in the brief are easy to miss and drive a lot of the design:

1. **`GET /shorten/{shortCode}` returns JSON, not a 301 redirect.** The brief says the frontend is
   responsible for taking the original URL out of that JSON and sending the browser onwards. So the
   API is a data service; the redirect lives one layer up.
2. **Short codes are random and unique.** "Generated randomly" rules out sequential codes such as
   `1`, `2`, `3`, and "must be unique" means the database has to enforce it, not just application
   code.

Authentication and authorisation are explicitly not required. Neither is expiry, custom aliases or
rate limiting. This implementation sticks to the brief and lists the extras in
[section 13](#13-what-was-left-out-and-how-you-would-add-it) rather than silently inventing scope.

---

## 2. What was built

| | |
|---|---|
| Language | Java 17 (runs on 17 and newer; built and tested on JDK 21) |
| Framework | Spring Boot 3.3.5 |
| Web layer | Spring MVC on embedded Tomcat |
| Storage | Spring Data JPA + Hibernate; H2 in memory by default, MySQL profile provided |
| Validation | Jakarta Bean Validation with one custom constraint |
| API docs | OpenAPI 3 / Swagger UI, generated from the code |
| Tests | 28 automated tests - 19 end-to-end over HTTP, 9 unit tests |

Five endpoints, exactly as the brief specifies, plus a health check through the framework's own
error handling for unknown paths.

---

## 3. Architecture

The application is deliberately layered. Each layer only talks to the one below it, which is what
makes the pieces testable in isolation.

```
        HTTP request
             |
             v
   +-----------------------+      Reads the request, checks the body, picks the
   |  UrlController        |      HTTP status code. Contains no business rules.
   |  (controller package) |
   +-----------+-----------+
               |
               v
   +-----------------------+      The rules: generate a unique code, count an access,
   |  UrlShortenerService  |      turn "not found" into an exception, decide what a
   |  (service package)    |      transaction covers.
   +-----------+-----------+
               |
               v
   +-----------------------+      Spring Data JPA. Method names become queries; the
   |  ShortUrlRepository   |      hand-written JPQL does the atomic counter update.
   +-----------+-----------+
               |
               v
   +-----------------------+      A single table, "short_urls". The unique constraint on
   |  Database (H2/MySQL)  |      short_code is the last line of defence for uniqueness.
   +-----------------------+

   Cross-cutting, used by every layer:
     - Entity + DTOs     moving data between the database shape and the JSON shape
     - Validation        the rules a URL must satisfy before anything else happens
     - GlobalExceptionHandler   one consistent error body for every failure
```

Why the DTO/entity split matters: the database row and the JSON document are not the same thing.
The column is called `original_url` and the JSON field is `url`; the primary key is a `Long` in the
database and a **string** in JSON, because the brief shows `"id": "1"`. Keeping one class for both
would force those decisions to leak into each other.

---

## 4. Request life cycle

### Creating a short URL - `POST /shorten`

```
1. Tomcat accepts the TCP connection and hands the bytes to Spring MVC.
2. Jackson parses the JSON into a ShortenRequest record.
   - Invalid JSON                                    -> 400, handled by the exception handler.
3. Bean Validation checks the three constraints on the url field.
   - Missing, blank, too long, or not an http(s) URL  -> 400 with fieldErrors.
4. UrlController.create() calls UrlShortenerService.create(url).
5. The service asks ShortCodeGenerator for a random 7-character Base62 code.
6. repository.existsByShortCode() checks it is free; a collision means going back to step 5
   (up to 10 attempts).
7. repository.save() inserts the row. @PrePersist stamps createdAt and updatedAt.
   The unique constraint on short_code is enforced by the database here.
8. The entity is mapped to UrlResponse, which the controller wraps in
   201 Created with a Location header of /shorten/{shortCode}.
```

### Resolving a short URL - `GET /shorten/{shortCode}`

```
1. UrlController.retrieve() receives the path variable as a plain string.
2. The service looks the code up. Missing -> ShortUrlNotFoundException.
3. The access counter is incremented with one UPDATE statement inside the database.
4. The record is mapped to UrlResponse and returned with 200 OK.
5. If nothing was found, GlobalExceptionHandler turns the exception into a
   404 with the standard error body.
```

The other three endpoints follow the same shape. `PUT` re-uses the same validation as `POST`, and
`DELETE` returns 204 with no body at all.

---

## 5. The data model

One table, `short_urls`. One row is one short code.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `BIGINT` | Primary key, generated by the database (identity / auto-increment) |
| `original_url` | `VARCHAR(2048)` | The long URL. Not null |
| `short_code` | `VARCHAR(16)` | The code. Not null, **unique** |
| `created_at` | `TIMESTAMP` | UTC, set once on insert |
| `updated_at` | `TIMESTAMP` | UTC, refreshed on every update |
| `access_count` | `BIGINT` | Starts at 0, incremented on every successful lookup |

The entity that maps this table is `src/main/java/com/urlshorty/entity/ShortUrl.java`.

Three decisions worth understanding:

**Why a generated numeric primary key instead of using `short_code` as the key?** Short codes are
random strings that a user could conceivably want to change later, and they are the part of the row
that is public. A surrogate key keeps the public identifier and the database identity separate, so
changing one does not ripple through foreign keys. It also keeps indexes narrow.

**Why 2048 characters for the URL?** It is the practical ceiling browsers and proxies work with,
and it keeps a runaway request body from filling the table with megabytes of text. The API rejects
anything longer with a 400 rather than silently truncating it.

**Why is `short_code` only 16 characters when codes are 7?** The column has headroom so the code
length can be increased later (say to 10) without a schema change.

### Schema

The application creates the table itself from the entity
(`spring.jpa.hibernate.ddl-auto=update`), so there is nothing to run before the first start. The
equivalent SQL, for reference or for creating the schema by hand, is in `docs/schema.sql`.

---

## 6. Short codes

`src/main/java/com/urlshorty/util/ShortCodeGenerator.java`

### The alphabet

Codes are drawn from Base62: `0-9`, `A-Z`, `a-z`. Those characters are all unreserved in a URL path,
so a code never needs percent-encoding, and a mixed-case alphabet is denser than digits alone.

### The maths

With 7 characters there are 62^7 = **3,521,614,606,208** possible codes, about 3.5 trillion.

That number is what makes random generation workable. The chance that a newly generated code is
already taken is roughly

```
    codes already stored
  ------------------------
    3,521,614,606,208
```

so with a million short URLs in the table, the chance of a collision on any single draw is about
0.00003%. Two collisions in a row would be about one in ten quadrillion. That is why a simple retry
loop is an acceptable strategy instead of something clever like a counter or a hash of the URL.

### Randomness

`SecureRandom` is used rather than `java.util.Random`. `java.util.Random` is a linear congruential
generator: observe two consecutive values and you can predict every value that follows. Since a
short code is the only thing protecting a link from being guessed, predictability matters here.
`SecureRandom` is slower, but at seven characters per request the difference is irrelevant.

### Collisions and concurrency

Two separate mechanisms deal with codes already being in use:

1. **The retry loop in `UrlShortenerService.generateUniqueCode()`.** It draws a code, asks the
   database whether it exists, and repeats up to ten times. This handles the everyday case with a
   friendly flow.
2. **The unique constraint on `short_code`.** Two requests can generate the same code in the same
   millisecond, and the check-then-insert above cannot prevent that - it is a race. The database's
   unique constraint can. If the race happens, the second insert fails with a
   `DataIntegrityViolationException` and the transaction is rolled back, so the table never contains
   duplicates.

Being explicit about the difference between "unlikely" and "impossible" is the point here: the loop
makes collisions rare, the constraint makes them harmless.

---

## 7. Input validation

Validation happens in one place, before any business logic runs. The rules are declared on the
request object, `src/main/java/com/urlshorty/dto/ShortenRequest.java`:

```java
public record ShortenRequest(
        @NotBlank(message = "url must not be missing or empty")
        @Size(max = 2048, message = "url must not exceed 2048 characters")
        @ValidHttpUrl
        String url) {
}
```

### How the three constraints work

| Constraint | Rejects | Example that fails |
|------------|---------|--------------------|
| `@NotBlank` | missing, empty, whitespace-only | `{"url":"   "}` |
| `@Size(max = 2048)` | anything the database column could not hold | a 3,000-character URL |
| `@ValidHttpUrl` | anything that is not an absolute http(s) URL | `"ftp://example.com"`, `"/some/path"`, `"not a url"` |

`@Valid` on the controller method is what switches them on. Spring asks Hibernate Validator to check
the object as soon as Jackson has built it. If anything fails, Spring throws
`MethodArgumentNotValidException` before the controller body executes, and the exception handler
turns it into a 400.

### Why a custom constraint instead of a regex

`ValidHttpUrl` and `HttpUrlValidator` exist because URL validation with regular expressions is a
classic mistake: the patterns are unreadable, they are either too strict (rejecting valid URLs) or
too permissive (accepting nonsense), and they cannot be debugged.

The validator parses the value as a `java.net.URI` and checks three things:

1. It is absolute - it has a scheme.
2. The scheme is `http` or `https`, compared case-insensitively.
3. It has a non-blank host.

It also rejects any value containing whitespace, because a space in a URL breaks the redirect a
frontend would perform.

Two subtle choices in that class are worth being able to explain:

- **It returns `true` for null and blank input.** That looks wrong at first glance, but `@NotBlank`
  is already responsible for those values. If both constraints complained, the caller would get two
  messages for one mistake. Each constraint reports one specific problem.
- **`new URI(value)` is not the same as converting to `java.net.URL`.** `URI` only parses; it does not
  try to resolve anything over the network. Validation stays a pure, fast, offline check.

---

## 8. Error handling

`src/main/java/com/urlshorty/exception/GlobalExceptionHandler.java`

Every failure - whichever layer produced it - comes back in the same JSON shape:

```json
{
  "timestamp": "2026-10-01T10:53:42.201Z",
  "status": 404,
  "error": "Not Found",
  "message": "No short URL found for code 'nosuchcode'",
  "path": "/shorten/nosuchcode"
}
```

`@RestControllerAdvice` marks the class as a listener for exceptions from every controller. Each
method declares which exception it handles:

| Exception | Status | When it happens |
|-----------|--------|-----------------|
| `ShortUrlNotFoundException` | 404 | the short code is not in the database |
| `NoResourceFoundException` | 404 | the URL does not match any endpoint at all |
| `MethodArgumentNotValidException` | 400 | a value failed `@NotBlank`, `@Size` or `@ValidHttpUrl` |
| `HttpMessageNotReadableException` | 400 | the body is missing, empty or not valid JSON |
| anything else | 500 | a bug; the stack trace is logged and a generic message returned |

### Why centralise it

Without this class, an error would come back in Spring's default format, and the shape would depend
on which layer noticed the problem: framework JSON for a parsing error, a different document for a
validation error, and an HTML page for some others. A client would need three parsers. One advice
class means one parser, and it means the validation response can carry a `fieldErrors` map that
tells the caller *which* field was wrong and why:

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

### Why the 500 handler hides the details

`handleUnexpected` logs the full exception with `log.error(...)` and returns only
"An unexpected error occurred". Stack traces and internal messages can reveal package names, library
versions and database structure, and none of that helps the caller. The information goes to the
operator's log instead.

### The subtle one: `NoResourceFoundException`

In Spring Boot 3.2 and newer, a request for a path that matches no endpoint throws
`NoResourceFoundException`. If the advice only had a catch-all `Exception` handler, that would be
converted into a 500 - a wrong answer - so there is an explicit handler that maps it to 404 and keeps
the error format consistent.

---

## 9. Counting access

The brief asks for "statistics on the number of times a short URL has been accessed". The
implementation defines an access as **one successful `GET /shorten/{shortCode}`**. Creating,
updating and reading statistics do not count.

Because the brief puts the redirect in the frontend, the frontend's lookup *is* the access, so
counting there gives the number a reviewer expects. The alternative - adding a
`GET /{shortCode}` redirect endpoint to the API and counting there - is described in
[section 13](#13-what-was-left-out-and-how-you-would-add-it).

### The concurrency detail

The obvious implementation is wrong under load:

```java
// Do not do this.
entity.setAccessCount(entity.getAccessCount() + 1);
```

Two requests that read the value 4 at the same time both write 5, and one access is lost. Read,
modify, write is not atomic.

Instead the arithmetic happens inside the database, in one statement
(`ShortUrlRepository.incrementAccessCount`):

```sql
UPDATE short_urls SET access_count = access_count + 1 WHERE short_code = ?
```

The database serialises the two updates, so both are counted. The service then sets the same value on
the in-memory object so the response it returns shows the number it just wrote. That line is safe
precisely because the update query cleared the persistence context first: the entity is detached, so
the change cannot be written back a second time.

This is a good example of a rule worth stating out loud: **counters belong in the database, not in
Java.**

---

## 10. Timestamps

Timestamps are stored as `java.time.Instant`, which is a point on the UTC timeline with no time zone
attached, and serialised by `JacksonConfig` in one fixed format:

```
2026-10-01T10:53:42.118Z
```

That is ISO-8601 / RFC 3339 in UTC with millisecond precision.

### Three decisions inside that sentence

**UTC, not local time.** The server's own time zone is irrelevant to a stored instant. Storing UTC
means the value does not change meaning if the application is moved to a machine in another region,
and it sorts correctly as text.

**Millisecond precision, not seconds.** The brief's example shows `2021-09-01T12:00:00Z` - no
fractional part. Truncating to whole seconds would match that example character for character, but
it creates a visible bug: create a short URL and update it immediately, and `createdAt` and
`updatedAt` both read the same second, so the update looks like it did not happen. Milliseconds keep
the response honest. Any ISO-8601 parser handles both, and RFC 3339 explicitly allows fractional
seconds.

**A fixed pattern, registered once.** Jackson's default behaviour omits the fractional part when it
happens to be zero, so the same field would sometimes read `...42Z` and sometimes `...42.118Z`. A
client would have to cope with two shapes. `JacksonConfig` registers a serialiser that always writes
milliseconds, so there is exactly one shape.

### `createdAt` and `updatedAt` are maintained by the entity

Hibernate calls two methods on the entity at the right moments:

```java
@PrePersist void onCreate()   // sets both timestamps before the first INSERT
@PreUpdate  void onUpdate()   // moves updatedAt forward before every UPDATE
```

Putting this in the entity rather than in the service means it happens no matter which code path
modifies the row. Nobody can forget to update the timestamp.

### A bug worth knowing about, because it is a real one

The first version of the update endpoint built its response *before* the transaction reached the
database, and Hibernate does not fire `@PreUpdate` until it writes the `UPDATE` statement - which
normally happens when the transaction commits, after the method has returned. So `PUT` returned the
*old* `updatedAt`, while the next `GET` returned the new one. Two endpoints disagreeing about the
same field is exactly the kind of bug that survives a quick manual test, because it is invisible when
the update happens in the same second as the creation.

The fix is one word: `saveAndFlush` instead of `set...` and returning. Flushing forces the `UPDATE`
to be written, which fires `@PreUpdate`, so the timestamp in the response is the one actually stored.
`ShortenApiIntegrationTest.updateChangesTheUrl` locks this in: it asserts that `updatedAt` moved, that
`createdAt` did not, and that a following `GET` reports the same timestamp the `PUT` response did.

---

## 11. Testing

**28 automated tests, all passing.** Run them with `.\mvnw.cmd test` (Windows) or `./mvnw test`.

The suite is split by how much of the application each test exercises. That is deliberate: a test
that covers the whole stack tells you the feature works, and a test that covers one class tells you
*where* it broke when it does not.

### Level 1 - the generator (`ShortCodeGeneratorTest`, 3 tests)

Pure unit tests, no Spring, milliseconds to run.

- Codes are exactly 7 characters.
- Every character comes from the Base62 alphabet - checked over 1,000 generated codes.
- 10,000 codes in a row are all distinct. (This is not a guarantee of anything, it is a smoke test
  that catches a generator accidentally returning a constant or ignoring part of its own state.)

### Level 2 - the business rules (`UrlShortenerServiceTest`, 6 tests)

The service is instantiated with Mockito doubles standing in for the repository and the generator, so
these tests control exactly what the database would have said. That makes it possible to test things
that are impractical to provoke through HTTP:

- A generated code that is already taken is retried, and the third attempt is used.
- Ten consecutive collisions give up with a clear error and nothing is saved.
- Resolving a code calls the counter update exactly once.
- Resolving an unknown code never touches the counter and reports not-found.
- Reading statistics does not change the counter.

Those two collision tests are the reason this level exists. Provoking a real collision through the
HTTP API would need control over the random generator, which is exactly what a mock provides.

### Level 3 - the API over HTTP (`ShortenApiIntegrationTest`, 19 tests)

The whole application is started on an in-memory database and requests are pushed through the entire
stack - Tomcat's test equivalent, Spring MVC, validation, the service, Hibernate, the database - using
`MockMvc`. Every endpoint and every status code in the brief is covered:

| Group | What is asserted |
|-------|------------------|
| Create | 201 with all five fields, a `Location` header, a 7-character code, timestamps in the documented format, and exactly one row in the database |
| Create failures | 400 for a non-http scheme, a relative path, a blank URL, a missing `url` property, an over-long URL, and malformed JSON |
| Retrieve | 200 with the original URL; each lookup increments the counter; a brand-new code starts at 0 |
| Retrieve failures | 404 with the standard error body for an unknown code |
| Update | 200 with the new URL; `createdAt` preserved; `updatedAt` moved; the change survives a re-read |
| Update failures | 400 for an invalid URL, 404 for an unknown code |
| Delete | 204 with an empty body, the row is gone, and a following GET is a 404 |
| Delete failures | 404 for an unknown code |
| Other | an unknown path returns 404 in the same error format |

### Why tests get their own database

`src/test/resources/application.yml` configures a separate in-memory H2 instance named
`urlshorty-test`, and `ddl-auto` is `create-drop` there. Consequences: the tests never touch
development data, they do not depend on the order they run in, and they leave nothing behind. The
integration test additionally clears the table before each test, so one test cannot see another
test's rows.

### The check that is not a test

`scripts/smoke-test.ps1` is not part of the suite. It runs against a *running* server over real HTTP
and real JSON, which is how the project was verified before delivery:

```
  [PASS] POST /shorten returns 201                      201
  [PASS] response contains a 7 character code           True
  [PASS] Location header points at the new code         /shorten/b4cqzcP
  [PASS] three lookups were counted                     3
  [PASS] createdAt is preserved                         2026-10-01T10:56:01.096Z
  [PASS] updatedAt moves forward                        True
  [PASS] invalid URL returns 400                        400
  [PASS] deleted code is gone                           404
```

---

## 12. Design decisions

This is the section to read before any conversation about the project. Each row is a decision, the
alternative that was rejected, and the reason.

| Decision | Alternative | Why |
|----------|-------------|-----|
| Spring Boot 3 | Plain servlets, Javalin, Micronaut | The brief suggests it, it removes hundreds of lines of wiring, and its conventions are widely recognised. Copying the project into any Java team would look familiar |
| H2 in memory as the default | MySQL only | A reviewer can clone and run with zero setup. MySQL is one profile away for real use |
| Layered controller / service / repository | Logic in the controller | The rules are readable in one place and testable without HTTP. The controller stays a translation layer |
| Separate entity and DTOs | Serialising the entity directly | The database shape (`original_url`, `Long` id) and the JSON shape (`url`, string id) genuinely differ. Leaking one into the other would freeze both |
| `id` as a JSON string | JSON number | The brief's example is `"id": "1"`. It costs one `String.valueOf` and keeps clients that follow the brief working |
| Random Base62 codes | Sequential counter | A counter leaks how many URLs exist and makes every code guessable. Random codes leak nothing and cannot be enumerated |
| `SecureRandom` | `java.util.Random` | A short code is the only thing protecting a link. A predictable generator means guessable links - and possibly somebody else's private URL |
| 7-character codes | 4-6 characters | 62^6 = 56 billion is workable; 62^7 = 3.5 trillion makes collisions negligible and keeps the URL short enough to read out loud |
| Retry loop **and** a unique constraint | Either one alone | The loop handles the common case gracefully; the constraint is the only thing that actually prevents duplicates when two requests race. Belt and braces, but for different reasons |
| Custom `@ValidHttpUrl` constraint | A regular expression | Regexes for URLs are unreadable and wrong in both directions. Parsing with `java.net.URI` is checkable and testable |
| One JSON error shape from one advice class | Spring's default errors | A client needs one parser, and validation errors can name the offending field |
| Counter incremented by SQL | Read-modify-write in Java | Two simultaneous lookups lose one increment. The database performs the arithmetic atomically |
| Timestamps in milliseconds, fixed format | Whole seconds, Jackson default | Whole seconds make a fast update look like it did nothing; the default formatter changes shape depending on the value |
| `saveAndFlush` on update | Returning the entity directly | Hibernate refreshes `updatedAt` only when it writes the row, which otherwise happens after the response is built |
| Access counted on `GET /shorten/{code}` | A redirect endpoint inside the API | The brief gives redirects to the frontend, so the frontend's lookup is the access. Adding a second redirect endpoint would duplicate the contract |
| No authentication | API keys, OAuth | The brief says it is out of scope. Adding it without a design agreement would be inventing requirements |
| OpenAPI via springdoc | Hand-written API docs | The documentation is generated from the code, so it cannot fall out of date |

---

## 13. What was left out, and how you would add it

Every item below is a deliberate omission with a sketch of the implementation, so that "what would
you do next?" has a concrete answer.

### A frontend with the 301 redirect

The brief's architecture diagram shows a frontend with a catch-all route. A minimal version is a page
that reads the code from the path, calls `GET /shorten/{code}`, and issues
`window.location.replace(record.url)`. Server-side, the same thing in three lines of Spring:

```java
@GetMapping("/{shortCode}")
public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
    UrlResponse record = service.resolve(shortCode);   // this is also what counts the access
    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
            .location(URI.create(record.url()))
            .build();
}
```

The catch is route priority: `/{shortCode}` would compete with `/shorten`, `/h2-console` and
`/swagger-ui.html`. A frontend on its own origin, or an explicit prefix such as `/r/{code}`, avoids
that entirely - which is why the split in the brief's diagram is a sensible design and not just an
omission.

### Expiry

Add `expires_at TIMESTAMP NULL` to the entity, set it from an optional request field, and check it in
`findOrThrow`: a record past its expiry is treated as not found (or deleted by a scheduled job using
`@Scheduled`).

### Custom aliases

Accept an optional `customAlias` in `ShortenRequest`. Validate it against the same character rules as
generated codes, try to insert it, and let the unique constraint reject a duplicate - returning
`409 Conflict` rather than `400`, because the request was well-formed but the name is taken.

### Authentication

Add `spring-boot-starter-security`, then either an API key filter for machine clients or JWT bearer
tokens for users. Ownership means adding an `owner_id` column and filtering every query by the
authenticated principal.

### Rate limiting

For a single instance, a Bucket4j filter in front of `/shorten` is enough. For several instances the
counter has to live somewhere shared - Redis, typically - otherwise each instance enforces its own
limit.

### Better statistics

The current counter answers "how many times". To answer "when", add an `access_events` table with
`(short_code, occurred_at, referrer, user_agent)` and insert a row per access. Aggregations then come
from `GROUP BY`. This is where a URL shortener stops fitting comfortably in one table, so it should
be a conscious step rather than a default.

### Caching

Lookups are read-mostly and repetitive, which makes them a natural fit for a cache. Spring's
`@Cacheable` with a fixed-size Caffeine cache would remove most database reads; the counter update
would still go to the database. Note that this changes the access-count semantics unless the cache
miss path is the only one that counts.

### Schema migrations

`ddl-auto=update` is convenient and not safe for a team: nothing records what changed or when, and it
never drops columns. Adding Flyway means writing the schema once as `V1__create_short_urls.sql` and
switching `ddl-auto` to `validate`.

### Scaling out

Nothing in the application keeps state in memory, so running several instances behind a load
balancer works as soon as they share a database. The parts that would need attention are the shared
rate limit, the cache, and the generated-code collision rate (still negligible, see section 6).

---

## 14. Demonstrating it

Five commands, in this order, cover everything the brief asks for:

```powershell
# 0. start it
.\mvnw.cmd spring-boot:run
```

```powershell
# 1. create  -> 201 and a short code
$r = Invoke-RestMethod -Method POST http://localhost:8080/shorten -ContentType "application/json" `
     -Body '{"url":"https://www.example.com/some/long/url"}'
$r | Format-List

# 2. resolve -> 200 with the original URL, and it counts an access
Invoke-RestMethod http://localhost:8080/shorten/$($r.shortCode)

# 3. statistics -> accessCount of 1
Invoke-RestMethod http://localhost:8080/shorten/$($r.shortCode)/stats

# 4. update  -> 200, same code, new URL, later updatedAt
Invoke-RestMethod -Method PUT http://localhost:8080/shorten/$($r.shortCode) `
  -ContentType "application/json" `
  -Body '{"url":"https://www.example.com/some/updated/url"}'

# 5. delete -> 204, and the next lookup is a 404
Invoke-WebRequest -Method DELETE http://localhost:8080/shorten/$($r.shortCode) -SkipHttpErrorCheck
Invoke-WebRequest http://localhost:8080/shorten/$($r.shortCode) -SkipHttpErrorCheck
```

Then open <http://localhost:8080/swagger-ui.html> to show the same five operations documented from
the code itself. Or run `.\scripts\smoke-test.ps1` and let it print eighteen PASS lines.

---

## 15. Code map

Where everything lives, and what each file is responsible for.

| File | Responsibility |
|------|----------------|
| `UrlShortyApplication.java` | The `main` method. Starts Spring Boot, triggers component scanning and auto-configuration |
| `controller/UrlController.java` | The five HTTP endpoints. Reads requests, chooses status codes and headers, delegates everything else |
| `service/UrlShortenerService.java` | The business rules: unique code generation with retry, transactions, resolving, updating, deleting, statistics |
| `repository/ShortUrlRepository.java` | Database access. Derived queries plus the atomic counter `UPDATE` |
| `entity/ShortUrl.java` | The `short_urls` row: columns, constraints, and the two lifecycle callbacks that maintain the timestamps |
| `dto/ShortenRequest.java` | The request body and its three validation constraints |
| `dto/UrlResponse.java` | The response body for create, retrieve and update |
| `dto/StatsResponse.java` | The response body for the statistics endpoint |
| `dto/ApiError.java` | The single error body shape |
| `exception/ShortUrlNotFoundException.java` | The one application-specific exception |
| `exception/GlobalExceptionHandler.java` | Maps every exception to a status code and the standard error body |
| `validation/ValidHttpUrl.java` | The custom constraint annotation |
| `validation/HttpUrlValidator.java` | The URI-based check behind it |
| `util/ShortCodeGenerator.java` | Random Base62 codes from `SecureRandom` |
| `config/JacksonConfig.java` | One fixed ISO-8601 timestamp format for every response |
| `application.yml` | Default configuration: H2, port 8080, Swagger paths |
| `application-mysql.yml` | The MySQL profile, with credentials read from environment variables |

### Where to look first when something is wrong

| Symptom | Look at |
|---------|---------|
| A `400` when you expected success | `ShortenRequest` - which constraint did the value break? |
| A `404` for a code you know exists | the database: is it the same H2 instance? In-memory data disappears on restart |
| `updatedAt` looks wrong | `ShortUrl.onUpdate()` and the `saveAndFlush` call in the service |
| Duplicate codes in the table | the unique constraint in `ShortUrl`, and whether the schema was ever created without it |
| The application will not start on Java 8 | `JAVA_HOME`; Spring Boot 3 needs Java 17 or newer |
