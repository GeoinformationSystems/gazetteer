package net.climatecharts;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.*;

/**
 * Gazetter proxy for ClimateCharts server.
 * Wraps calls to Geonames and permits requests via HTTP / HTTPS.
 * 
 * @author Matthias Müller and Marcus Kossatz
 */

@Path("/")
public class Gazetteer
{

	// Shared HTTP client; created once per instance and reused across requests.
	private final transient CloseableHttpClient httpClient;

	@Context
	ServletContext servletContext;

	/** Default constructor: uses a fresh HttpClient. */
	public Gazetteer()
	{
		this(HttpClients.createDefault());
	}

	/** Package-private constructor for tests: inject a mocked HttpClient. */
	Gazetteer(CloseableHttpClient httpClient)
	{
		this.httpClient = httpClient;
	}

	String geonamesConfig(String key)
	{
		String value = servletContext.getInitParameter(key);
		if (value == null || value.isBlank())
		{
			throw new IllegalStateException(
				"Missing required <context-param> " + key + ". "
				+ "Configure it in web.xml or override via Tomcat context.xml.");
		}
		return value;
	}

	/** Fetches the response body of the given URL as a String. Package-private
	 *  for tests; throws IOException so callers can map to a 5xx response. */
	String fetch(java.net.URI uri) throws IOException
	{
		try (CloseableHttpResponse response = httpClient.execute(new HttpGet(uri)))
		{
			return EntityUtils.toString(response.getEntity());
		}
	}

	/* priority list: location codes
	 * in which order of priority shall location name be found?
	 * i.e. if a result in first priority list entry is found, it is taken
	 * if not, look in the second entry, and so on...
	 */
	private static final String[] locationCodes = {
		"PPLC",
		"PPLG",
		"PPLA",
		"PPLA2",
		"PPLA3",
		"PPLA4",
		"PPL",
		"PPLL",
		"PPLS",
		"PPLH",
		"PPLR",
	};
	
	
	/* find place name:
	 * get a list of all larger places in the area with radius stated above and
	 * then filter out the result that seems most reasonable
	 * https://secure.geonames.org/findNearbyJSON?lat=51.0516&lng=13.7349
	 * 	&featureClass=P&featureCode=PPL&featureCode=PPLA&featureCode=PPLA2
	 * 	&featureCode=PPLA3&featureCode=PPLA4&featureCode=PPLC&featureCode=PPLG
	 * 	&featureCode=PPLH&featureCode=PPLL&featureCode=PPLR&featureCode=PPLS
	 * 	&radius=5&localCountry=true&maxRows=100&username=yourusername
	 *
	 * find elevation:
	 * https://secure.geonames.org/srtm3JSON?lat=51.7465&lng=14.3206
	 * 	&username=yourusername
	 */
	
	
	@GET
	@Path("/getName")
	@Produces("application/json")
	public String getName(
				@QueryParam("lat") Double 	lat,
				@QueryParam("lng") Double 	lng,
                                @QueryParam("rad") Integer 	rad
			)
	{
		if (lat == null || lng == null)
		{
			throw new WebApplicationException(Response.Status.BAD_REQUEST);
		}

		int locationRadius = (rad == null) ? 10 : rad;

		try
		{
			// feature codes: https://www.geonames.org/export/codes.html
			URIBuilder uri = new URIBuilder(geonamesConfig("geonames.findNearbyUrl"))
				.addParameter("lat", lat.toString())
				.addParameter("lng", lng.toString())
				.addParameter("featureClass", "P");

			for (int i=0; i<locationCodes.length; i++)
				uri.addParameter("featureCode", locationCodes[i]);

			uri.addParameter("radius", Integer.toString(locationRadius))
				.addParameter("maxRows", "100")
				.addParameter("username", geonamesConfig("geonames.username"));

			// response: JSON string
			final String dataStr = fetch(uri.build());

			JSONObject dataObj = new JSONObject(dataStr);

			if (dataObj.has("status"))
			{
				JSONObject status = dataObj.getJSONObject("status");
				int code = status.optInt("value", -1);
				String message = status.optString("message", "unknown Geonames error");
				throw geonamesErrorResponse(code, message);
			}

			JSONArray resultLocations = dataObj.optJSONArray("geonames");
			if (resultLocations == null)
			{
				throw geonamesErrorResponse(-1, "Geonames response had neither 'geonames' nor 'status'");
			}

			// 2nd level: array of location results
			/* put each location into a bucket for its location code
			 * -> ordered by priority in the list "locationCodes"
			 * data structure: array of list of JSONObjects (locations)
			 * 	[
			 * 	  <List>[{JSONObject}, {JSONObject}, ...],	// 1. priority location code (e.g. "PPLC")
			 * 	  <List>[{JSONObject}, {JSONObject}, ...],	// 2. priority location code (e.g. "PPLG")
			 * 	  ...
			 *    <List>[{JSONObject}, {JSONObject}, ...],	// last priority location code (e.g. "PPLR")
			 * 	]
			 */
			List<List<JSONObject>> resultLocationsByPriority = new ArrayList<>();
			for (int i=0; i<locationCodes.length; i++)
				resultLocationsByPriority.add(new ArrayList<>());

			// put the result locations in the correct list
			for (int i=0; i<resultLocations.length(); i++)
			{
				JSONObject resultLocation = resultLocations.getJSONObject(i);

				// get location code
				String resultLocationCode = resultLocation.getString("fcode");

				// find priority number of this location code
				int resultLocationCodeIdx = -1;
				for (int j=0; j<locationCodes.length; j++)
				{
					if (locationCodes[j].equals(resultLocationCode))
					{
						resultLocationCodeIdx = j;
						break;
					}
				}

				// unknown fcode (Geonames occasionally returns feature codes
				// we didn't request, e.g. PPLX). Skip rather than crash.
				if (resultLocationCodeIdx >= 0)
				{
					resultLocationsByPriority.get(resultLocationCodeIdx).add(resultLocation);
				}
			}

			// priority: find the first array in which there is at least one result location
			// and take that location with the largest population (default: first result)
			for (int i=0; i<resultLocationsByPriority.size(); i++)
			{
				// check if there is a location code in this priority list
				if (!resultLocationsByPriority.get(i).isEmpty())
				{
					// default result: the first result in the list, because it is the closest
					JSONObject bestResultLocation = resultLocationsByPriority.get(i).get(0);
					int largestLocationPopulation = bestResultLocation.getInt("population");

					// check if there is any location in this priority list that has a larger population
					ListIterator<JSONObject> listIterator = resultLocationsByPriority.get(i).listIterator();

					// no need to check the first element => skip it = already go to first element
					listIterator.next();

					while (listIterator.hasNext())
					{
						// get the current location and its population
						JSONObject currentResultLocation = listIterator.next();
						int currentLocationPopulation = currentResultLocation.getInt("population");

						// is the current location larger?
						if (currentLocationPopulation > largestLocationPopulation)
						{
							// update best result location
							bestResultLocation = currentResultLocation;
							largestLocationPopulation = currentLocationPopulation;
						}
					}

					// the result was definitely found! => return it
					return bestResultLocation.toString();
				}
			}

			// in case no result was in the result set => return empty result
			return new JSONObject().toString();
		}
		catch (IOException e)
		{
			e.printStackTrace();
			throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
		}
		catch (URISyntaxException e)
		{
			e.printStackTrace();
			throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
		}
	}

	@GET
	@Path("/getElevation")
	@Produces("application/json")
	public String getElevation(
				@QueryParam("lat") Double 	lat,
				@QueryParam("lng") Double 	lng
			)
	{
		if (lat == null || lng == null)
		{
			throw new WebApplicationException(Response.Status.BAD_REQUEST);
		}

		try
		{
			final java.net.URI uri = new URIBuilder(geonamesConfig("geonames.srtm3Url"))
					.addParameter("lat", lat.toString())
					.addParameter("lng", lng.toString())
					.addParameter("username", geonamesConfig("geonames.username"))
					.build();

			return fetch(uri);
		}
		catch (IOException e)
		{
			e.printStackTrace();
			throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
		}
		catch (URISyntaxException e)
		{
			e.printStackTrace();
			throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
		}
	}

	private static WebApplicationException geonamesErrorResponse(int code, String message)
	{
		String body = "{\"error\":\"geonames_error\",\"code\":" + code
			+ ",\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
		return new WebApplicationException(
			Response.status(Response.Status.BAD_GATEWAY)
				.type(MediaType.APPLICATION_JSON)
				.entity(body)
				.build());
	}
}
