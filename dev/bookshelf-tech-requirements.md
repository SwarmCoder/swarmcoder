# Bookshelf — how it must be built

This is the technical side of the Bookshelf app. The other document says what the
app must do for the person using it. This one says what it must be made of.

None of the rules below is a feature. Nothing here is ever "finished" — every one
of them applies to every piece of work on this project, for as long as the project
exists.

## The stack is fixed and it is not the usual one

This app is built on **ZeroZ Stack**, version 0.9.1, on **Java 21** with
**Maven**. That is not a preference. The whole point of the stack is that there is
one language from the browser down to the disk, so anything that reintroduces a
second language or a translation layer defeats it.

The most important thing to understand: **this looks like an ordinary Java web app
and is not one.** Almost every habit from Spring, JPA and REST is wrong here. If
you find yourself reaching for something familiar, that is the moment to check.

## Explicitly forbidden

Do not use, add, import, or write tests against any of these. None of them is
present in this project and none of them will be added:

- **Spring or Spring Boot** in any form
- **JPA, Hibernate, or any ORM**, and the `jakarta.persistence` annotations
- **Flyway, Liquibase, or SQL migrations** — there is no SQL and no schema
- **A relational database** of any kind
- **REST endpoints, HTTP controllers, or JSON** as the way the browser talks to
  the server
- **JavaScript or TypeScript** source files
- **Vaadin**, or any other web UI framework

If a piece of work seems to need one of these, the design is wrong. Say so rather
than adding it.

## The three modules, and what belongs in each

The repository is a Maven aggregator with three modules. There is no source code
at the top level and there never should be.

- `bookshelf-demo-shared` — the data model, and the service interfaces the browser
  calls. Both sides depend on it.
- `bookshelf-demo-client` — everything the browser runs.
- `bookshelf-demo-server` — everything the server runs.

Existing package root: `com.swarmcoder.demo.bookshelf`. Stay inside it.

## The browser runs Java

The client module is compiled from Java to JavaScript by **TeaVM**. You write Java;
the build produces the browser code. This means the client can only use classes
TeaVM can compile, which is a subset of the Java library — not everything on the
server side is available.

The user interface is built from the stack's own components in `com.zeroz4j.ui.*`
— for example `Div`, `Component`, `TextStyle`. Build screens by composing those in
Java. Do not write HTML templates and do not hand-write CSS-in-Java where a
component already exists.

## The browser talks to the server by calling Java methods

There are no REST calls and no JSON. A service interface lives in the shared
module; the client calls it as if it were local, and the stack carries the call
over a binary WebSocket connection. The client connects with
`Zeroz4jClient.connect(...)`.

Objects that cross that boundary are plain Java classes annotated `@DataModel`,
with public fields and a no-argument constructor. `Message` in the shared module is
the working example — copy its shape. An annotation processor (`zerozstack-apt`)
generates what the transport needs, so a data class that does not compile cleanly
through the processor will fail in ways that look unrelated.

## Storage is an object graph, not a database

Persistence uses **EclipseStore** through `zerozstack-store-eclipsestore`. The
server keeps the live Java objects in memory and writes that object graph to disk.
There is no SQL, no schema, no mapping layer, and no queries in the database sense
— you hold a root object and walk it with ordinary Java.

**The one rule that catches everybody:** saving an object does **not** automatically
save the objects inside it. Storing a list does not store a book that was already
in it and has since been edited. Every level of nesting you changed needs its own
explicit save call. Getting this wrong loses data silently — the app looks correct
until it is restarted, and then the edit is simply gone. This has caused real data
loss before and it is the single most likely way to get this project wrong.

## Packaging

The server jar is deliberately **not** a shaded or fat jar, and must not become
one. The CDI container treats each jar as a separate bean archive, and merging them
breaks discovery in ways that surface a long way from the cause. Keep the module
jars separate on a plain classpath.

## Building and checking your work

The build is offline. `mvn -o` — the sandbox has no network, and a build that tries
to reach the internet fails in a way that looks like a mistake in the code.

Compile from the repository root so the three modules build in the right order.
Code that compiles is the minimum bar, not the goal; the app has to actually run.

## Where to find the real answers

The full ZeroZ Stack documentation is available to you as reference material —
43 documents, searchable. **Look things up there before working an API out from
compiled classes.** Reading class files to guess at a method signature is slow,
usually wrong, and has repeatedly exhausted an entire work budget without producing
a single line of code. If the documentation genuinely does not answer it, say so.
