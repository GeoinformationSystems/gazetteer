package net.climatecharts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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

import jakarta.ws.rs.WebApplicationException;

public class GazetteerTest
{
	// ---------- Fixtures --------------------------------------------------

	/** Mock-friendly Gazetteer that captures the URI of the outgoing request
	 *  instead of building it from a Geonames endpoint we can't reach in CI. */
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

	private CloseableHttpClient http;
	private CloseableHttpResponse response;
	private HttpEntity entity;

	@Before
	public void setUp() throws IOException
	{
		http    = mock(CloseableHttpClient.class);
		response = mock(CloseableHttpResponse.class);
		entity  = mock(HttpEntity.class);

		when(http.execute(any())).thenReturn(response);
		when(response.getEntity()).thenReturn(entity);
	}

	/** Helper: mock an HttpClient whose next response body is the given JSON string. */
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

		Gazetteer g = new Gazetteer(clientReturning(body));
		String result = g.find("getName", 50.0, 10.0, 20);

		assertTrue("expected PPLA result, got: " + result, result.contains("\"BigRegion\""));
		assertTrue(result.contains("\"fcode\":\"PPLA\""));
	}

	@Test
	public void getName_withinSameBucket_picksLargestPopulation() throws Exception
	{
		// Two PPL results: pick the one with the larger population.
		String body = "{ \"geonames\": ["
			+ "{\"fcode\":\"PPL\", \"name\":\"VillageA\", \"population\":500},"
			+ "{\"fcode\":\"PPL\", \"name\":\"CityB\",    \"population\":200000}"
			+ "] }";

		Gazetteer g = new Gazetteer(clientReturning(body));
		String result = g.find("getName", 50.0, 10.0, 20);

		assertTrue(result.contains("\"CityB\""));
	}

	@Test
	public void getName_emptyResponse_returnsEmptyObject() throws Exception
	{
		String body = "{ \"geonames\": [] }";

		Gazetteer g = new Gazetteer(clientReturning(body));
		String result = g.find("getName", 50.0, 10.0, 20);

		assertEquals("{}", result);
	}

	// ---------- Edge cases ------------------------------------------------

	/** Geonames occasionally returns features with an fcode we didn't ask for
	 *  (e.g. PPLX when we only requested P, PPL, PPLA, ...). The current
	 *  implementation must not crash with ArrayIndexOutOfBoundsException.
	 *
	 *  TODO(phase 6): fix the negative-index bug in Gazetteer.java line 189
	 *  before re-enabling. */
	@Ignore("Known bug; fixed in Phase 6 (refactor: harden parsing).")
	@Test
	public void getName_unknownFcode_isIgnored_notThrown() throws Exception
	{
		String body = "{ \"geonames\": ["
			+ "{\"fcode\":\"PPLX\", \"name\":\"IgnoredSection\", \"population\":999999}"
			+ "] }";

		Gazetteer g = new Gazetteer(clientReturning(body));
		String result = g.find("getName", 50.0, 10.0, 20);

		// The unknown fcode is dropped; no crash, no result.
		assertEquals("{}", result);
	}

	@Test
	public void find_missingLat_throws400() throws Exception
	{
		Gazetteer g = new Gazetteer(clientReturning(""));
		try {
			g.find("getName", null, 10.0, 20);
			assertTrue("expected WebApplicationException for missing lat", false);
		} catch (WebApplicationException e) {
			assertEquals(400, e.getResponse().getStatus());
		}
	}

	@Test
	public void find_unknownOp_throws400() throws Exception
	{
		Gazetteer g = new Gazetteer(clientReturning(""));
		try {
			g.find("getBogus", 50.0, 10.0, 20);
			assertTrue("expected WebApplicationException for unknown op", false);
		} catch (WebApplicationException e) {
			assertEquals(400, e.getResponse().getStatus());
		}
	}

	// ---------- Request shape: verify what we send to Geonames -----------

	@Test
	public void getName_uriContainsFeatureCodesAndRadius() throws Exception
	{
		RecordingGazetteer g = new RecordingGazetteer(clientReturning("{}"));

		g.find("getName", 51.0516, 13.7349, 25);

		assertNotNull(g.lastUri);
		String host = g.lastUri.getHost();
		String q = g.lastUri.getRawQuery();
		// Regression: api.geonames.org has no valid TLS cert; the SSL cert is
		// served for secure.geonames.org. Call the latter or HttpClient raises
		// SSLPeerUnverifiedException at request time.
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
		RecordingGazetteer g = new RecordingGazetteer(clientReturning("{}"));

		g.find("getName", 51.0516, 13.7349, null);

		String q = g.lastUri.getRawQuery();
		assertTrue("default radius should be 10, got: " + q, q.contains("radius=10"));
	}

	@Test
	public void getElevation_returnsResponseBodyVerbatim() throws Exception
	{
		String body = "{\"srtm3\":123,\"lng\":10.0,\"lat\":50.0}";
		Gazetteer g = new Gazetteer(clientReturning(body));

		String result = g.find("getElevation", 50.0, 10.0, null);

		assertEquals(body, result);
	}

	// ---------- Live-call smoke test (kept @Ignore'd for manual debugging) -

	/** Live call to api.geonames.org. Skipped by default because CI must not
	 *  hit external services. Run manually when debugging geocoding behaviour. */
	@Ignore("Live call to geonames; run manually only.")
	@Test
	public void live_smokeTest() throws Exception
	{
		String elevation = new Gazetteer().find("getElevation", 51.0516, 13.7349, null);
		String name      = new Gazetteer().find("getName",      51.0516, 13.7349, 20);
		System.out.println("elevation=" + elevation);
		System.out.println("name=" + name);
	}
}
