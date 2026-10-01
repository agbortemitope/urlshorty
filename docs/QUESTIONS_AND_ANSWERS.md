# Questions and answers

Everything below is written so it can be said out loud in a review, an interview, or a conversation
with a colleague. The answers are short on purpose and point at the code when more detail helps.

---

## Contents

- [A. The 60-second explanation](#a-the-60-second-explanation)
- [B. Requirements and behaviour](#b-requirements-and-behaviour)
- [C. Architecture and code](#c-architecture-and-code)
- [D. Database and data model](#d-database-and-data-model)
- [E. Short codes](#e-short-codes)
- [F. Validation and errors](#f-validation-and-errors)
- [G. Counting access](#g-counting-access)
- [H. Testing](#h-testing)
- [I. Security](#i-security)
- [J. Production and scaling](#j-production-and-scaling)
- [K. Self-assessment](#k-self-assessment)
- [L. Glossary](#l-glossary)

---

## A. The 60-second explanation

**What is this?**

> It is a URL shortening service, built as a REST API in Java with Spring Boot. It turns a long URL
> into a random seven-character code, resolves that code back to the original URL, and lets you
> update it, delete it, and see how many times it was used. It stores data in a single table, uses an
> in-memory H2 database by default so it runs with no setup, and has a MySQL profile for real use.
> There are 28 automated tests covering every endpoint and status code in the specification.

**Why does that matter?**

> The interesting parts are not the CRUD. They are the details: generating codes that cannot be
> guessed, making sure two requests can never create the same code, and counting accesses correctly
> when several requests arrive at once. Those are the three places where a naive implementation is
> broken in a way that only shows up under load.

---

## B. Requirements and behaviour

**1. What does the API actually expose?**

> Five endpoints, exactly as the specification describes them:
>
> | Method | Path | Success |
> |---|---|---|
> | POST | `/shorten` | 201 with the new record |
> | GET | `/shorten/{shortCode}` | 200 with the original URL |
> | PUT | `/shorten/{shortCode}` | 200 with the updated record |
> | DELETE | `/shorten/{shortCode}` | 204, no body |
> | GET | `/shorten/{shortCode}/stats` | 200 with the record plus `accessCount` |
>
> Failures are 400 for a bad request, 404 for an unknown code, and 500 for a bug.

**2. Why doesn't `GET /shorten/{shortCode}` return a 301 redirect?**

> Because the specification says so, and it is a reasonable split. The API answers with JSON; the
> frontend is responsible for reading the `url` field and sending the browser onwards. That keeps the
> API a pure data service that a mobile app or a server can use just as easily as a browser, and it
> means the redirect policy is a presentation decision rather than an API decision.

**3. So is there no redirect at all?**

> Not in the API, by design. Section 13 of the project guide shows how a frontend or a preview route
> would add one in about three lines, and explains why a catch-all route needs care: it would compete
> with `/shorten`, `/h2-console` and the Swagger UI path.

**4. What happens if I shorten the same URL twice?**

> You get two different short codes pointing at the same URL. The service does not deduplicate, and
> that is intentional: two people shortening the same popular link should get their own code, so that
> deleting or editing one does not affect the other. Deduplicating would also mean the statistics of
> two unrelated users got mixed together.

**5. Can I choose my own short code?**

> No. The specification asks for randomly generated codes, and custom aliases bring their own
> problems - squatting, reserved words, moderation. Section 13 of the guide sketches how to add them
> if the requirement appears: validate the alias, try to insert it, and let the unique constraint
> answer with a 409 Conflict when it is taken.

**6. What is not implemented?**

> Authentication, expiry, custom aliases, rate limiting and per-visit analytics. All of them are
> listed as out of scope in the specification or deliberately deferred, and each one has a short
> design sketch in the project guide so they are not vague hand-waving.

**7. What happens when I update a URL - does the access count reset?**

> No. Updating changes the target URL and moves `updatedAt`; the code and the counter are untouched.
> The counter describes how popular the short link is, and that did not change.

**8. What does deleting do?**

> It removes the row and returns 204. A second delete, or a lookup of the deleted code, returns 404.
> There is no soft delete, so the code can be reused by a future random draw - which is fine at 3.5
> trillion codes and matters more if you add custom aliases.

---

## C. Architecture and code

**9. Why Spring Boot?**

> The specification suggests it, and it is the right size for this: it wires up an embedded server,
> JSON, validation, a connection pool and an ORM with no XML, so the code is about the URL shortener
> and not about infrastructure. It is also what a Java team would recognise immediately, and its
> conventions make the project easy to hand over.

**10. Walk me through the layers.**

> Four, each only talking to the one below:
>
> - **Controller** - HTTP. Reads the request, picks the status code and headers, delegates.
> - **Service** - the rules: generate a unique code, count an access, open transactions.
> - **Repository** - Spring Data JPA. Method names become queries.
> - **Database** - one table. The unique constraint is the final authority on codes.
>
> Plus three things used across all of them: the entity, the DTOs, and the exception handler.

**11. Why not put everything in the controller?**

> Because then the rules could only be tested through HTTP, and the HTTP concerns would be tangled
> with the business ones. Keeping them apart is why the collision tests can run in milliseconds with
> a mocked repository instead of needing to force a real collision through the API.

**12. Why do you have both an entity and DTOs?**

> Because they are genuinely different things. The row calls the URL `original_url` and the JSON calls
> it `url`; the primary key is a `Long` in the database and a string in JSON. If one class served
> both, a change to the database schema would silently change the public API, and the other way
> around. The mapping is four lines in `UrlResponse.from()` and it buys that independence.

**13. What is dependency injection doing here?**

> `UrlShortenerService` does not create its repository or its generator; it asks for them in its
> constructor, and Spring passes them in. That is what allows the unit tests to pass in mocks. It
> also means the service has no idea whether it is running in a test, a dev environment or
> production.

**14. What does `@Transactional` do on a service method?**

> It wraps the method in a database transaction: everything succeeds together or nothing is applied.
> That is what makes "check the code is free, then insert it" a single unit of work, and it means a
> failure rolls the whole thing back rather than leaving half a change behind.

**15. Why is `open-in-view` disabled?**

> Spring Boot enables it by default, which keeps a database session open for the whole web request so
> lazy loading works anywhere. That hides accidental queries in the view layer and holds connections
> longer than needed. Disabling it makes every database access explicit and inside the service, and
> if something tries to load data too late, it fails loudly in development instead of quietly in
> production. It is one line in `application.yml`.

**16. Where would you look if a `PUT` returned the wrong `updatedAt`?**

> That bug actually happened during development, and it is documented in section 10 of the project
> guide. Hibernate only refreshes `updatedAt` when it writes the `UPDATE`, which normally happens at
> commit - after the response has been built - so the response carried the old value while the next
> `GET` showed the new one. The fix is `saveAndFlush` on the update path, and there is now an
> integration test asserting the timestamp in the `PUT` response matches what a following `GET`
> returns.

---

## D. Database and data model

**17. Describe the schema.**

> One table, `short_urls`: `id` (generated primary key), `original_url` (up to 2048 characters),
> `short_code` (unique, not null), `created_at`, `updated_at` and `access_count`. One row is one short
> link. The unique constraint on `short_code` is the important one.

**18. Why a separate numeric id if the short code is already unique?**

> The short code is the public identifier and the primary key is the internal one. Keeping them apart
> means the code could be changed or rotated later without touching foreign keys, and it keeps
> indexes narrower. It also means a bug in code generation cannot corrupt referential integrity.

**19. If the service checks whether a code exists before inserting, why the unique constraint?**

> Because the check and the insert are two separate statements, so two requests can both pass the
> check and then both try to insert the same code. The constraint is what makes that impossible: the
> database rejects the second insert. The check exists to keep the common case friendly; the
> constraint exists to make the rare case harmless.

**20. Why 2048 characters for the URL?**

> It is roughly the practical limit browsers and proxies cope with, and it stops a huge body from
> filling the table. Requests over the limit are rejected with a 400 and a clear message instead of
> being silently truncated, which would produce a link that goes to the wrong place.

**21. Where is the data stored, and what happens on restart?**

> By default it is an in-memory H2 database, so everything disappears when the process stops - which
> is exactly what you want for a demo, since the project always starts clean. For persistence, the
> `mysql` profile points the same code at a MySQL schema, with credentials supplied as environment
> variables so nothing secret lives in the repository.

**22. Why is the schema created by Hibernate instead of a migration?**

> Because it removes a setup step for anyone who clones the project: `ddl-auto=update` builds the
> table from the entity. It is the wrong choice for a team, and the guide says so - there is no record
> of what changed or when, and it never removes a column. The production answer is a migration tool
> such as Flyway with `ddl-auto` switched to `validate`, which is a small, contained change.

**23. Why is `short_code` 16 characters when codes are 7?**

> Headroom. If the code length ever needs to grow - say to 10 characters to extend the space before a
> custom-alias feature lands - the column does not need an `ALTER TABLE`.

---

## E. Short codes

**24. How are short codes generated?**

> Seven characters drawn at random from a 62-character alphabet: digits, upper case and lower case.
> The generator uses `SecureRandom`, and the service checks the result against the database, retrying
> up to ten times if it is already taken.

**25. Why Base62?**

> Those characters are safe in a URL path without percent-encoding, and letters plus digits give more
> combinations per character than digits alone. With 7 characters that is 62^7, about 3.5 trillion
> possible codes.

**26. Why 7 characters and not 4 or 5?**

> The trade-off is space against collisions and guessability. Four characters is only 14.7 million
> codes - the birthday problem starts biting after a few thousand links - and short codes are easier
> to enumerate. Seven keeps the code readable while making collisions negligible and enumeration
> impractical.

**27. What is the chance of a collision?**

> Roughly the number of codes already stored divided by 3.5 trillion. With a million links in the
> table, any single draw has about a 0.00003% chance of landing on one that exists. Two collisions in
> a row - which is what it would take to give up, since the service retries ten times - is around one
> in ten quadrillion.

**28. What happens if a collision does occur?**

> First the retry loop: it draws a new code and checks again. If a code were somehow to slip past the
> check and two inserts raced, the unique constraint rejects the second one and its transaction rolls
> back, so the table never contains duplicates. The API would return an error for that one request
> rather than corrupting data.

**29. Why not derive the code from the URL with a hash?**

> Hashing makes codes deterministic, which sounds appealing - the same URL gives the same code - but
> it creates its own problems. It needs a hash long enough to resist collisions, which fights with
> keeping the code short; truncating a hash reintroduces collisions anyway; and deduplication means
> two users shortening the same link share a code, so one deleting it breaks the other's link and
> their statistics mix. Random generation with a uniqueness check is simpler and gives each user
> their own code.

**30. Why `SecureRandom` and not `Random`?**

> `Random` is a predictable linear generator: observe a couple of outputs and you can compute the
> rest. Since the short code is the only thing protecting a link, predictability means guessable
> links and potentially somebody else's private URL. `SecureRandom` costs a little more per call,
> which is irrelevant at seven characters per request.

**31. Why is the retry limited to ten attempts?**

> So a broken generator cannot spin forever inside a request. Ten failures in a row would mean
> something structural is wrong - the code space is effectively full, or the generator is stuck - and
> in that situation failing fast with a clear error beats hanging. It is a guard rail, not a
> realistic limit.

**32. Could two users get the same code?**

> No. They could in theory collide while generating, in which case one of them gets a different code
> from the retry, and the database constraint makes duplicates impossible even under a race. What two
> users can legitimately have is the same *target URL* under two different codes.

---

## F. Validation and errors

**33. What counts as a valid URL here?**

> An absolute `http` or `https` URL with a host and no whitespace. So `https://example.com/page`
> passes, while `ftp://example.com`, `/some/path`, `not a url` and `https://exa mple.com` all fail
> with a 400 naming the field.

**34. How is validation wired up?**

> The constraints are declared on the request record - `@NotBlank`, `@Size(max = 2048)` and a custom
> `@ValidHttpUrl` - and `@Valid` on the controller method tells Spring to check them right after
> Jackson builds the object. A failure throws before the controller body runs, and the exception
> handler turns it into a 400 with a `fieldErrors` map.

**35. Why write a custom constraint instead of a regular expression?**

> URL regexes are the classic example of a pattern nobody can read, maintain, or trust. Mine parses
> the string as a `java.net.URI` and checks three things: it is absolute, the scheme is http or
> https, and it has a host. It is a dozen readable lines, and it is unit-testable. The validator also
> deliberately returns "valid" for blank input, because `@NotBlank` is already handling that case and
> two messages for one mistake would be noise.

**36. Why one error format everywhere?**

> So a client needs one parser, and so validation failures can say *which* field was wrong. Without a
> central handler, the format depends on which layer noticed the problem - framework JSON here, a
> different document there - and clients end up with three code paths for errors.

**37. Why does the 500 response not include the exception message?**

> Because internal messages leak package names, library versions and database structure, and none of
> that helps the caller. The full stack trace is logged for whoever operates the service. The client
> gets a stable message and a correlation point in the logs.

**38. Why is `id` a string in the JSON when it is a number in the database?**

> The specification's examples show `"id": "1"`. Following the documented contract matters more than
> matching the database type, and it costs one `String.valueOf` in the mapper. Identifiers that
> clients only ever pass back are usually safer as strings anyway, since they cannot be
> arithmetic-ed away by mistake.

**39. What are the status codes, and why those?**

> 201 for a created resource, with a `Location` header pointing at it - that is the HTTP definition
> of a successful create. 200 for reads and updates that return a body. 204 for a delete, because
> there is nothing meaningful to return. 400 when the request is at fault, 404 when the resource does
> not exist, 500 when the server is at fault. The distinction between 400 and 404 matters to clients:
> one means "fix your request", the other means "this code is gone".

---

## G. Counting access

**40. How does the access counter work?**

> Every successful `GET /shorten/{shortCode}` increments it by one, inside the database, using a
> single statement: `UPDATE short_urls SET access_count = access_count + 1 WHERE short_code = ?`. The
> statistics endpoint reads the value without changing it.

**41. Why not just increment the number in Java?**

> Because that is a read-modify-write, and it loses updates. If two requests both read the value 4
> and both write 5, one access has vanished. Doing the arithmetic in the `UPDATE` statement lets the
> database serialise the two operations, so the count ends up at 6. Counters belong in the database.

**42. Could the count still be wrong?**

> Not for concurrent lookups, which is the realistic risk. It is not a transaction-perfect
> "exactly once" count in a distributed sense: if a request increments the counter and the response
> then never reaches the client, that access is still counted. For statistics that is the correct
> trade-off - you want to know how often the link was fetched, not how often the client noticed.

**43. Does reading the statistics count as an access?**

> No. Only resolving a code counts. Otherwise checking your own statistics would inflate the number,
> which would be surprising.

**44. Someone reloads the same link ten times - that is ten accesses?**

> Yes. The count is "how many times was this code resolved", which is the definition the
> specification asks for. De-duplicating by IP address or session would make the number less
> trustworthy, not more: shared networks, privacy tools and caches would all distort it. If unique
> visitors are ever needed, that is a separate metric built from an access-events table, not a
> replacement for the raw count.

---

## H. Testing

**45. How many tests are there, and what kinds?**

> Twenty-eight, at three levels. Three unit tests for the code generator - length, alphabet, no
> repeats over ten thousand draws. Six unit tests for the service with a mocked repository, which is
> how collisions and give-up behaviour get tested. And nineteen integration tests that start the
> whole application and push real HTTP requests through it, covering every endpoint, every status code
> in the specification, and every validation rule.

**46. Why mock the repository in the service tests?**

> To control the answers, so that situations which are almost impossible to produce for real become
> easy to test. The clear example is a code collision: with a mock, the test simply says "the first
> two generated codes are already taken" and then asserts the service draws a third, uses it, and
> saves once. Through the real API, provoking that would mean controlling `SecureRandom`.

**47. How do tests avoid destroying your data?**

> They use a completely separate in-memory database, `urlshorty-test`, with the schema created and
> dropped per run, and the integration test clears the table before each test. So tests are
> independent of each other, independent of the order they run in, and cannot touch a developer's
> data.

**48. What did the tests actually catch?**

> The stale-timestamp bug. The update endpoint was returning the *previous* `updatedAt` because
> Hibernate does not refresh it until it writes the row, which happens after the response is built.
> It was invisible in a manual check, because the create and the update landed in the same second and
> the two timestamps looked identical anyway. The test that now locks it down asserts that `updatedAt`
> moved forward, that `createdAt` did not, and that a following `GET` returns the same timestamp the
> `PUT` response returned.

**49. How do you run them, and what is not covered?**

> `.\mvnw.cmd test` on Windows or `./mvnw test` elsewhere; the whole suite runs in well under a
> minute. What is not covered: load and concurrency behaviour under real traffic, MySQL-specific
> behaviour (the tests run on H2), and the Spring Boot wiring that only breaks at startup in a
> different profile. Those are the honest gaps, and each has a clear way to close it - a JMeter or
> Gatling run for load, a Testcontainers-based MySQL test for the database, and a smoke test per
> profile for the wiring. `scripts/smoke-test.ps1` is the beginning of that last one.

---

## I. Security

**50. Is this secure?**

> It is secure in the ways the specification asks for and honestly incomplete in the ways it does not.
> Codes are generated with `SecureRandom`, so they cannot be predicted from each other; input is
> validated before it reaches anything; there is no authentication, which the specification explicitly
> excludes. So: nobody can guess their way to somebody else's link, but anybody who knows a code can
> delete it. The second half is a scope decision, not an oversight.

**51. Could someone enumerate codes?**

> Not practically. With 62^7 - about 3.5 trillion - combinations, and no way to tell a valid code from
> an invalid one other than asking, brute force is hopeless: at a thousand requests per second you
> would cover 0.0025% of the space in a year. Rate limiting would make even that pointless, and it
> would be the first thing to add if the service were public.

**52. Any injection risk?**

> No SQL injection: every statement goes through JPA with bound parameters, so user input is never
> concatenated into a query. Note that this is also why the URL is not validated with a home-made
> string check - the value is stored as data, and the redirect that would use it lives in a frontend
> which would need its own protection against open-redirect abuse.

**53. What about spam and abuse?**

> Anyone can create unlimited short URLs. The standard answers are rate limiting per IP or API key,
> a blocklist of known malicious domains, and authentication so abuse is attributable. None of them
> is in the specification, and each is a design conversation rather than a one-line change.

**54. Is there anything unsafe in the configuration?**

> The defaults are development defaults, and the guide says so: the H2 console is enabled and the
> database is in memory. For a deployment the H2 console would be off, credentials would come from
> the environment - which is how the MySQL profile already works - and the schema would be managed by
> migrations instead of `ddl-auto`.

---

## J. Production and scaling

**55. How would you scale this to more traffic?**

> The application keeps no state in memory, so the first step is simply running several instances
> behind a load balancer with a shared database. That handles far more than a URL shortener usually
> needs. Beyond that, lookups are read-mostly and repetitive, so a cache in front of resolution
> removes most database reads - remembering that the counter update still has to reach the database.

**56. What would break first?**

> Honestly, nothing dramatic at small scale; a single instance and a single MySQL server would handle
> a lot. The realistic first pressure points are the access counter, because every lookup writes, and
> the single table itself. Both are solved the same way: read replicas plus a cache for lookups, and
> letting writes stay on the primary.

**57. Why is there no cache now?**

> Because at this scale it would add a moving part and change the access-count semantics - a cache
> hit that never reaches the service does not count as an access - without solving a problem the
> project has. The guide sketches where it would go when it is needed.

**58. How would you add expiry?**

> Add a nullable `expires_at` column, accept an optional field when creating, and check it during
> lookup - a record past its expiry is treated as not found. A scheduled job can delete expired rows
> afterwards, but the lookup check is what makes expiry correct; the job is just housekeeping.

**59. How would you make the statistics richer?**

> Add an `access_events` table with the code, timestamp, referrer and user agent, and write one row
> per access. Then "how many times" becomes "how many times, when, and from where", at the cost of one
> insert per lookup instead of one update. That is the point where a URL shortener stops fitting in a
> single table, so it should be a deliberate step.

**60. What if two people delete the same code at once?**

> One succeeds and the other gets a 404. Both delete paths look the record up first, and the second
> one finds nothing. There is no partial state to worry about.

---

## K. Self-assessment

**61. What is the weakest part of the project?**

> The statistics. A single counter answers "how many" but nothing else, and it is incremented on
> every lookup, which makes the write path heavier than the read path for a service whose reads
> dominate. The second-weakest is that `ddl-auto` manages the schema - fine for this project, wrong
> for a team.

**62. What would you do differently with more time?**

> Add Flyway so schema changes are versioned, add the access-events table so statistics can answer
> "when", add rate limiting, and run the integration tests against a real MySQL in a container so the
> MySQL profile is covered by tests rather than by inspection.

**63. What was the most interesting problem?**

> The collision handling, because it is the place where the obvious answer is not enough. Checking
> whether a code exists looks like it prevents duplicates, but between the check and the insert
> another request can do the same thing. Understanding that only the database can actually guarantee
> uniqueness - and that the application-level check is there for tidiness, not safety - is the
> difference between code that works in testing and code that works under load.

**64. If you had to redo it in a different language, what would stay the same?**

> The design, which is language-independent: random codes with a retry, a unique constraint as the
> real guarantee, the counter incremented by the database, one consistent error shape, and timestamps
> in UTC. Those are decisions about the problem, not about Java.

---

## L. Glossary

| Term | Meaning in this project |
|------|-------------------------|
| **REST** | A style of API where URLs name resources and HTTP verbs describe the action: POST creates, GET reads, PUT updates, DELETE removes |
| **Entity** | A Java class mapped to a database table. Here, `ShortUrl` |
| **DTO** | Data Transfer Object - a class whose only job is to shape data for the API, separate from the database row |
| **Bean** | An object that Spring creates and manages. Anything annotated `@Component`, `@Service`, `@RestController`, `@Configuration` and friends |
| **Dependency injection** | Asking for what you need in a constructor instead of creating it yourself, which is what lets tests substitute mocks |
| **JPA / Hibernate** | The standard for mapping Java objects to database rows. Hibernate is the implementation |
| **Spring Data JPA** | The layer that turns repository method names into queries, so `findByShortCode` needs no implementation |
| **JPQL** | The query language used in `@Query` annotations - like SQL, but written against entities instead of tables |
| **Transaction** | A group of database operations that all succeed or all fail together. Declared with `@Transactional` |
| **Surrogate key** | An artificial primary key (`id`) with no meaning outside the database, as opposed to a natural key such as the short code |
| **Base62** | An alphabet of 62 characters: `0-9`, `A-Z`, `a-z`. Used to build short codes |
| **Collision** | Two short URLs generating the same code |
| **Race condition** | A bug that appears only when two operations overlap in time, such as check-then-insert |
| **`SecureRandom`** | A random number generator that cannot be predicted from its previous outputs |
| **Bean Validation** | The standard behind `@NotBlank`, `@Size` and custom constraints such as `@ValidHttpUrl` |
| **`@RestControllerAdvice`** | A Spring class that handles exceptions from every controller in one place |
| **`MockMvc`** | A test tool that sends requests through the full Spring web stack without opening a real network port |
| **Mock / Mockito** | A stand-in object that returns scripted answers, used to test one class in isolation |
| **`ddl-auto`** | The Hibernate setting that decides whether it creates or updates the schema automatically |
| **H2** | An in-memory database used here so the project runs with no installation |
| **OpenAPI / Swagger UI** | A machine-readable description of the API, plus a web page that lets you call the endpoints from a browser |
| **ISO-8601 / RFC 3339** | The standard timestamp format used in responses: `2026-10-01T10:53:42.118Z`. The trailing `Z` means UTC |
