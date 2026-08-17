package net.climatecharts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.WebApplicationException;

public class GazetteerTest
{
	// ---------- Fixtures --------------------------------------------------

	/** Records the URI of each outgoing request for assertion in tests. */
	private static class RecordingGazetteer extends Gazetteer
	{
		java.net.URI lastUri;

		RecordingGazetteer(CloseableHttpClient client) { super(client); }

		@Override
		String fetch(java.net.URI uri) throws IOException
		{
			this.lastUri = uri;
			// Return a minimal but valid Geonames response so the parser
			// doesn't choke before the test can inspect the URI.
			return "{\"geonames\":[]}";
		}
	}

	/** Mocks an HttpClient whose next response body is the given JSON string. */
	private static CloseableHttpClient clientReturning(String jsonBody) throws IOException
	{
		CloseableHttpClient http    = mock(CloseableHttpClient.class);
		CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
		HttpEntity ent = mock(HttpEntity.class);

		when(http.execute(any())).thenReturn(resp);
		when(resp.getEntity()).thenReturn(ent);
		when(ent.getContent()).thenReturn(new ByteArrayInputStream(jsonBody.getBytes(StandardCharsets.UTF_8)));

		return http;
	}

	/** Default <context-param> values shipped in web.xml. Tests stub these
	 *  into the mocked ServletContext; individual tests override as needed. */
	private static final String USERNAME      = "yourusername";
	private static final String SRTM3_URL     = "https://secure.geonames.org/srtm3JSON";
	private static final String FIND_NEAR_URL = "https://secure.geonames.org/findNearbyJSON";

	private ServletContext servletContext;
	private CloseableHttpClient http;
	private CloseableHttpResponse response;
	private HttpEntity entity;

	@Before
	public void setUp() throws IOException
	{
		// HTTP layer
		http     = mock(CloseableHttpClient.class);
		response = mock(CloseableHttpResponse.class);
		entity   = mock(HttpEntity.class);
		when(http.execute(any())).thenReturn(response);
		when(response.getEntity()).thenReturn(entity);

		// Servlet context: ships the same <context-param> defaults that
		// the project bundles in web.xml.
		servletContext = mock(ServletContext.class);
		when(servletContext.getInitParameter("geonames.username"))
			.thenReturn(USERNAME);
		when(servletContext.getInitParameter("geonames.srtm3Url"))
			.thenReturn(SRTM3_URL);
		when(servletContext.getInitParameter("geonames.findNearbyUrl"))
			.thenReturn(FIND_NEAR_URL);
	}

	/** Helper: builds a Gazetteer wired to the mocked HttpClient + the mocked
	 *  ServletContext. All non-fixture tests go through this. */
	private Gazetteer newGazetteer()
	{
		Gazetteer g = new Gazetteer(http);
		g.servletContext = servletContext;
		return g;
	}

	/** Same as newGazetteer() but returns a RecordingGazetteer so tests can
	 *  inspect the URI of the outgoing request. */
	private RecordingGazetteer newRecorder()
	{
		RecordingGazetteer g = new RecordingGazetteer(http);
		g.servletContext = servletContext;
		return g;
	}

	// ---------- Happy path: pick highest priority, then largest population ----------

	@Test
	public void getName_picksHighestPriorityBucket() throws Exception
	{
		// Two PPL results and one PPLA. PPLA has higher priority than PPL,
		// even though the PPL is listed first in the response.
		String body = "{ \"geonames\": ["
			+ "{\"fcode\":\"PPL\",  \"name\":\"SmallTown\",   \"population\":50000},"
			+ "{\"fcode\":\"PPLA\", \"name\":\"BigRegion\",   \"population\":8000}"
			+ "] }";

		CloseableHttpClient client = clientReturning(body);
		Gazetteer g = new Gazetteer(client);
		g.servletContext = servletContext;
		String result = g.getName(50.0, 10.0, 20);

		assertTrue("expected PPLA result, got: " + result, result.contains("\"BigRegion\""));
		assertTrue(result.contains("\"fcode\":\"PPLA\""));
	}

	@Test
	public void getName_withinSameBucket_picksLargestPopulation() throws Exception
	{
		String body = "{ \"geonames\": ["
			+ "{\"fcode\":\"PPL\", \"name\":\"VillageA\", \"population\":500},"
			+ "{\"fcode\":\"PPL\", \"name\":\"CityB\",    \"population\":200000}"
			+ "] }";

		CloseableHttpClient client = clientReturning(body);
		Gazetteer g = new Gazetteer(client);
		g.servletContext = servletContext;
		String result = g.getName(50.0, 10.0, 20);

		assertTrue(result.contains("\"CityB\""));
	}

	@Test
	public void getName_emptyResponse_returnsEmptyObject() throws Exception
	{
		String body = "{ \"geonames\": [] }";

		CloseableHttpClient client = clientReturning(body);
		Gazetteer g = new Gazetteer(client);
		g.servletContext = servletContext;
		String result = g.getName(50.0, 10.0, 20);

		assertEquals("{}", result);
	}

	// ---------- Edge cases ------------------------------------------------

	/** Geonames occasionally returns features with an fcode we didn't ask for
	 *  (e.g. PPLX when we only requested P, PPL, PPLA, ...). The implementation
	 *  must skip those silently rather than crashing with
	 *  ArrayIndexOutOfBoundsException. */
	@Test
	public void getName_unknownFcode_isIgnored_notThrown() throws Exception
	{
		String body = "{ \"geonames\": ["
			+ "{\"fcode\":\"PPLX\", \"name\":\"IgnoredSection\", \"population\":999999}"
			+ "] }";

		CloseableHttpClient client = clientReturning(body);
		Gazetteer g = new Gazetteer(client);
		g.servletContext = servletContext;
		String result = g.getName(50.0, 10.0, 20);

		// The unknown fcode is dropped; no crash, no result.
		assertEquals("{}", result);
	}

	// ---------- Bad-request validation -----------------------------------

	@Test
	public void getName_missingLat_throws400() throws Exception
	{
		Gazetteer g = newGazetteer();
		try {
			g.getName(null, 10.0, 20);
			fail("expected WebApplicationException for missing lat");
		} catch (WebApplicationException e) {
			assertEquals(400, e.getResponse().getStatus());
		}
	}

	@Test
	public void getElevation_missingLat_throws400() throws Exception
	{
		Gazetteer g = newGazetteer();
		try {
			g.getElevation(null, 10.0);
			fail("expected WebApplicationException for missing lat");
		} catch (WebApplicationException e) {
			assertEquals(400, e.getResponse().getStatus());
		}
	}

	// ---------- Request shape: verify what we send to Geonames -----------

	@Test
	public void getName_uriContainsFeatureCodesAndRadius() throws Exception
	{
		RecordingGazetteer g = newRecorder();

		g.getName(51.0516, 13.7349, 25);

		assertNotNull(g.lastUri);
		String host = g.lastUri.getHost();
		String q = g.lastUri.getRawQuery();
		// The endpoint URL is read from <context-param> "geonames.findNearbyUrl";
		// the test's default-stub uses the canonical secure.geonames.org host
		// (api.geonames.org has no valid TLS cert — see the geonames docs).
		assertEquals("secure.geonames.org", host);
		assertEquals("https", g.lastUri.getScheme());
		assertTrue("missing lat in query: " + q,      q.contains("lat=51.0516"));
		assertTrue("missing lng in query: " + q,      q.contains("lng=13.7349"));
		assertTrue("missing radius in query: " + q,   q.contains("radius=25"));
		assertTrue("missing featureClass=P: " + q,    q.contains("featureClass=P"));
		assertTrue("missing PPLC featureCode: " + q,   q.contains("featureCode=PPLC"));
		assertTrue("missing PPL featureCode: " + q,    q.contains("featureCode=PPL"));
		assertTrue("missing username: " + q,          q.contains("username="));
	}

	@Test
	public void getName_radiusDefaultsTo10WhenOmitted() throws Exception
	{
		RecordingGazetteer g = newRecorder();

		g.getName(51.0516, 13.7349, null);

		String q = g.lastUri.getRawQuery();
		assertTrue("default radius should be 10, got: " + q, q.contains("radius=10"));
	}

	@Test
	public void getElevation_returnsResponseBodyVerbatim() throws Exception
	{
		String body = "{\"srtm3\":123,\"lng\":10.0,\"lat\":50.0}";
		CloseableHttpClient client = clientReturning(body);
		Gazetteer g = new Gazetteer(client);
		g.servletContext = servletContext;

		String result = g.getElevation(50.0, 10.0);

		assertEquals(body, result);
	}

	// ---------- Geonames error-response handling (Phase 6.4) ------------

	@Test
	public void getName_geonamesErrorResponse_returns502() throws Exception
	{
		// Geonames returns this shape on auth failure, rate limit, etc.
		String body = "{ \"status\": {"
			+ "\"message\": \"user does not exist.\","
			+ "\"value\": 18"
			+ "} }";

		CloseableHttpClient client = clientReturning(body);
		Gazetteer g = new Gazetteer(client);
		g.servletContext = servletContext;

		try {
			g.getName(51.0516, 13.7349, 20);
			fail("expected WebApplicationException for Geonames error response");
		} catch (WebApplicationException e) {
			assertEquals(502, e.getResponse().getStatus());
			String responseBody = e.getResponse().getEntity().toString();
			assertTrue("error body should include geonames code: " + responseBody,
				responseBody.contains("\"code\":18"));
			assertTrue("error body should include geonames message: " + responseBody,
				responseBody.contains("\"message\":\"user does not exist.\""));
		}
	}

	@Test
	public void getName_unexpectedResponse_returns502() throws Exception
	{
		// Neither 'geonames' nor 'status' in the response — Geonames sent us
		// something we don't know how to parse. Should not propagate a
		// JSONException up as a 500.
		String body = "{ \"weird\": \"shape\" }";

		CloseableHttpClient client = clientReturning(body);
		Gazetteer g = new Gazetteer(client);
		g.servletContext = servletContext;

		try {
			g.getName(51.0516, 13.7349, 20);
			fail("expected WebApplicationException for unexpected response shape");
		} catch (WebApplicationException e) {
			assertEquals(502, e.getResponse().getStatus());
			String responseBody = e.getResponse().getEntity().toString();
			assertTrue("error body should mention neither geonames nor status: " + responseBody,
				responseBody.contains("neither 'geonames' nor 'status'"));
		}
	}

	// ---------- Home page (welcome file) -------------------------------

	@Test
	public void viewHome_returnsIndexHtmlContent() throws Exception
	{
		String html = "<html><body>Welcome to gazetteer</body></html>";
		when(servletContext.getResourceAsStream("/index.html"))
			.thenReturn(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
		Gazetteer g = newGazetteer();

		jakarta.ws.rs.core.Response r = g.viewHome();

		assertEquals(200, r.getStatus());
		assertEquals(html, r.getEntity());
	}

	@Test
	public void viewHome_returns404WhenIndexHtmlMissing() throws Exception
	{
		when(servletContext.getResourceAsStream("/index.html"))
			.thenReturn(null);
		Gazetteer g = newGazetteer();

		jakarta.ws.rs.core.Response r = g.viewHome();

		assertEquals(404, r.getStatus());
	}

	// ---------- Geonames config loading from web.xml ---------------------

	@Test
	public void geonamesConfig_readsUsernameFromServletContext() throws Exception
	{
		Gazetteer g = newGazetteer();

		assertEquals(USERNAME, g.geonamesConfig("geonames.username"));
	}

	@Test
	public void geonamesConfig_readsSrtm3UrlFromServletContext() throws Exception
	{
		Gazetteer g = newGazetteer();

		assertEquals(SRTM3_URL, g.geonamesConfig("geonames.srtm3Url"));
	}

	@Test
	public void geonamesConfig_readsFindNearbyUrlFromServletContext() throws Exception
	{
		Gazetteer g = newGazetteer();

		assertEquals(FIND_NEAR_URL, g.geonamesConfig("geonames.findNearbyUrl"));
	}

	@Test
	public void geonamesConfig_throwsWhenContextParamMissing() throws Exception
	{
		when(servletContext.getInitParameter("geonames.username"))
			.thenReturn(null);
		Gazetteer g = newGazetteer();

		try {
			g.geonamesConfig("geonames.username");
			fail("expected IllegalStateException for missing <context-param>");
		} catch (IllegalStateException e) {
			assertTrue("error message should mention <context-param>: " + e.getMessage(),
				e.getMessage().contains("<context-param>"));
		}
	}

	@Test
	public void geonamesConfig_throwsWhenContextParamBlank() throws Exception
	{
		when(servletContext.getInitParameter("geonames.username"))
			.thenReturn("   ");
		Gazetteer g = newGazetteer();

		try {
			g.geonamesConfig("geonames.username");
			fail("expected IllegalStateException for blank <context-param>");
		} catch (IllegalStateException e) {
			assertTrue("error message should mention <context-param>: " + e.getMessage(),
				e.getMessage().contains("<context-param>"));
		}
	}

	// ---------- Production override pattern via Tomcat context.xml ------

	@Test
	public void getName_uriUsesOverriddenUsername() throws Exception
	{
		// Simulate the production override pattern: Tomcat's per-app
		// context.xml sets geonames.username to a different value than the
		// web.xml default. The runtime value must reach the request URI.
		when(servletContext.getInitParameter("geonames.username"))
			.thenReturn("my-prod-account");
		RecordingGazetteer g = newRecorder();

		g.getName(51.0516, 13.7349, 10);

		String q = g.lastUri.getRawQuery();
		assertTrue("overridden username not in query: " + q,
				q.contains("username=my-prod-account"));
	}

	@Test
	public void getName_uriUsesOverriddenFindNearbyUrl() throws Exception
	{
		// Override the endpoint URL to a hypothetical private Geonames mirror.
		// The request URI must reflect the override value, not the web.xml
		// default — otherwise per-deployment redirects are impossible.
		when(servletContext.getInitParameter("geonames.findNearbyUrl"))
			.thenReturn("https://mirror.internal.example.com/findNearbyJSON");
		RecordingGazetteer g = newRecorder();

		g.getName(51.0516, 13.7349, 10);

		String host = g.lastUri.getHost();
		String path = g.lastUri.getPath();
		assertEquals("mirror.internal.example.com", host);
		assertEquals("/findNearbyJSON", path);
	}

	@Test
	public void getElevation_uriUsesOverriddenSrtm3Url() throws Exception
	{
		when(servletContext.getInitParameter("geonames.srtm3Url"))
			.thenReturn("https://mirror.internal.example.com/srtm3JSON");
		RecordingGazetteer g = newRecorder();

		g.getElevation(51.0516, 13.7349);

		String host = g.lastUri.getHost();
		String path = g.lastUri.getPath();
		assertEquals("mirror.internal.example.com", host);
		assertEquals("/srtm3JSON", path);
	}

	// ---------- Live-call smoke test (kept @Ignore'd for manual debugging) -

	/** Live call to secure.geonames.org. Skipped by default because CI must
	 *  not hit external services. Run manually when debugging geocoding. */
	@Ignore("Live call to geonames; run manually only.")
	@Test
	public void live_smokeTest() throws Exception
	{
		String elevation = new Gazetteer().getElevation(51.0516, 13.7349);
		String name      = new Gazetteer().getName(51.0516, 13.7349, 20);
		System.out.println("elevation=" + elevation);
		System.out.println("name=" + name);
	}
}
