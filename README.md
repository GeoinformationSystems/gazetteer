# gazetteer
The Gazetteer server application for the climatecharts web application. Returns a JSON string with location information given geographic coordinates.
The project is built on Jakarta EE 9 for a servlet engine like Tomcat 10. **Java 17 or newer** is required to build and run.

### Building

The project ships with the Maven Wrapper, so a system-wide Maven installation is not needed.

```bash
./mvnw clean package        # Linux / macOS / Git Bash
mvnw.cmd clean package      # Windows cmd
```

The resulting WAR is at `target/gazetteer.war`.

### Running

Deploy the WAR to a Jakarta-EE-compatible servlet container. Tested with:

- Apache Tomcat 10.1.x (Jakarta EE 9 / Servlet 5.0)
- (Older versions of this codebase, pre-Jakarta-migration, supported Tomcat 9.)

For Tomcat 10 the `javax.*` → `jakarta.*` namespace migration has been completed; the WAR **will not** run on Tomcat 9 any more.

### Configuration

Geonames API parameters are read at request time from the servlet context. Defaults are set in `src/main/webapp/WEB-INF/web.xml` as `<context-param>` entries. For production deployments, override per environment via Tomcat's `context.xml`:

```xml
<!-- /etc/tomcat10/Catalina/localhost/gazetteer.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<Context>
	<Parameter name="geonames.username" override="false" value="<yourusername>"/>
	<Parameter name="geonames.srtm3Url" override="false" value="https://secure.geonames.org/srtm3JSON"/>
	<Parameter name="geonames.findNearbyUrl" override="false" value="https://secure.geonames.org/findNearbyJSON"/>
</Context>
```

Note: the `<Parameter>` elements must use `override="false"` so the per-deployment values take precedence over the defaults in `web.xml`.

### Usage

Open `http://server:port/gazetteer` in a browser for a short overview page.

* `/getName`: returns the best-matching populated place near the given coordinates, picked by feature-code priority and by largest population. Returns a single Geonames JSON object, or `{}` if nothing is found within the radius.
	- `lat`: latitude in decimal degrees, e.g. `51.0516` (Dresden). Required.
	- `lng`: longitude in decimal degrees, e.g. `13.7349` (Dresden). Required.
    - `rad`: search radius in kilometers around the point. Defaults to `10`.
* `/getElevation`: returns the SRTM-3 elevation at the given coordinates as a Geonames JSON object (`{"srtm3": <meters>, "lat": ..., "lng": ...}`).
	- `lat`: latitude in decimal degrees. Required.
	- `lng`: longitude in decimal degrees. Required.

Example:

```bash
curl "http://localhost:8080/gazetteer/getName?lat=51.0516&lng=13.7349&rad=20"
curl "http://localhost:8080/gazetteer/getElevation?lat=51.0516&lng=13.7349"
```

### Testing

```bash
./mvnw test         # unit tests only
./mvnw verify       # unit tests + WAR packaging
```

# License

The gazetteer project is licensed under the Apache License 2.0.

## Java Libraries

This project uses a collection of Java libraries:

| Library | Version | License |
|---|---|---|
| `jakarta.servlet:jakarta.servlet-api` | 5.0.0 (provided) | EPL 2.0 + GPL2 (classpath exception) |
| `org.json:json` | 20231013 | The JSON License |
| `commons-io:commons-io` | 2.15.1 | Apache 2.0 |
| `org.apache.commons:commons-lang3` | 3.14.0 | Apache 2.0 |
| `org.apache.httpcomponents:httpclient` | 4.5.14 | Apache 2.0 |
| `org.glassfish.jersey.core:jersey-server` | 3.1.5 | EPL 2.0 + GPL2 (classpath exception) |
| `org.glassfish.jersey.containers:jersey-container-servlet` | 3.1.5 | EPL 2.0 + GPL2 (classpath exception) |
| `org.glassfish.jersey.inject:jersey-hk2` | 3.1.5 | EPL 2.0 + GPL2 (classpath exception) |
| `jakarta.xml.bind:jakarta.xml.bind-api` | 3.0.1 | EPL 2.0 + GPL2 (classpath exception) |
| `org.glassfish.jaxb:jaxb-runtime` | 3.0.2 | BSD-3-Clause |

**Test-only dependencies:**

| Library | Version | License |
|---|---|---|
| `junit:junit` | 4.13.1 | EPL 1.0 |
| `org.mockito:mockito-core` | 5.11.0 | MIT |
