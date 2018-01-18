package net.climatecharts;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.utils.URIBuilder;
import org.json.*;

/**
 * Gazetter proxy for ClimateCharts server.
 * Wraps calls to Geonames and permits requests via HTTP / HTTPS.
 * 
 * @author Matthias Müller and Marcus Kossatz
 */

@Path("/{op}")
public class Gazetteer
{	
	private static final String srtm3Url = 		"http://api.geonames.org/srtm3JSON";
	private static final String placenameUrl = 	"http://api.geonames.org/findNearbyJSON";
	private static final String geonamesUser = 	"climatediagrams";
	
	// how many kilometers away from the given point shall be searched for the next location?
	private static int 	locationRadius = 0;
	
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
	 * http://api.geonames.org/findNearbyJSON?lat=51.0516&lng=13.7349
	 * 	&featureClass=P&featureCode=PPL&featureCode=PPLA&featureCode=PPLA2
	 * 	&featureCode=PPLA3&featureCode=PPLA4&featureCode=PPLC&featureCode=PPLG
	 * 	&featureCode=PPLH&featureCode=PPLL&featureCode=PPLR&featureCode=PPLS
	 * 	&radius=5&localCountry=true&maxRows=100&username=climatediagrams
	 * 
	 * find elevation:
	 * http://api.geonames.org/srtm3JSON?lat=51.7465&lng=14.3206
	 * 	&username=climatediagrams
	 */
	
	
	@GET
	@Produces("application/json")
	public String find(
				@PathParam("op") String 	op,
				@QueryParam("lat") Double 	lat,
				@QueryParam("lng") Double 	lng,
                                @QueryParam("rad") Integer 	rad
			)
	{	
		if (lat == null || lng == null)
		{
			throw new WebApplicationException(Response.Status.BAD_REQUEST);
		}
                if (rad == null)
                {
                    locationRadius = 10;
                }
                else
                {
                    locationRadius = rad;
                }
		
		if (op.equals("getName"))
		{
			try
			{
				// feature codes: http://www.geonames.org/export/codes.html
				URIBuilder uri = new URIBuilder(placenameUrl)
					.addParameter("lat", lat.toString())
					.addParameter("lng", lng.toString())
					.addParameter("featureClass", "P");
				
				for (int i=0; i<locationCodes.length; i++)
					uri.addParameter("featureCode", locationCodes[i]);
				
				uri.addParameter("radius", Integer.toString(locationRadius))
//					.addParameter("localCountry", "true")
					.addParameter("maxRows", "100")
					.addParameter("username", geonamesUser);
				
				URL url = uri.build().toURL();
				
				// response: JSON string
				final String dataStr = IOUtils.toString(url);
				
				// convert JSON string to JSON object to read it
				JSONObject dataObj = new JSONObject(dataStr);
								
				// 1st level: object "geonames"
				JSONArray resultLocations = (JSONArray) dataObj.get("geonames");
				// has only one child: array of location results
				
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
				List<JSONObject>[] resultLocationsByPriority = new List[locationCodes.length];
				for (int i=0; i<resultLocationsByPriority.length; i++)
					resultLocationsByPriority[i] = new ArrayList<>();

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
					
					// put the location result in the correct array
					resultLocationsByPriority[resultLocationCodeIdx].add(resultLocation);
				}
				
				// priority: find the first array in which there is at least one result location
				// and take that location with the largest population (default: first result)
				for (int i=0; i<resultLocationsByPriority.length; i++)
				{
					// check if there is a location code in this priority list
					if (!resultLocationsByPriority[i].isEmpty())
					{
						// default result: the first result in the list, because it is the closest
						JSONObject bestResultLocation = resultLocationsByPriority[i].get(0);
						int largestLocationPopulation = bestResultLocation.getInt("population");
						
						// check if there is any location in this priority list that has a larger population
						ListIterator<JSONObject> listIterator = resultLocationsByPriority[i].listIterator();
						
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
		
		else if (op.equals("getElevation"))
		{
			// http://api.geonames.org/srtm3JSON?lat=21.15&lng=46.38&username=climatediagrams
			try
			{
				final URL url = new URIBuilder(srtm3Url)
						.addParameter("lat", lat.toString())
						.addParameter("lng", lng.toString())
						.addParameter("username", geonamesUser)
						.build().toURL();
				
				final String data = IOUtils.toString(url);
				return data;
			}
			catch (IOException e)
			{
				e.printStackTrace();
				throw new WebApplicationException();
			}
			catch (URISyntaxException e)
			{
				e.printStackTrace();
				throw new WebApplicationException();
			}
		}
		else
		{
			throw new WebApplicationException(Response.Status.BAD_REQUEST);
		}
	}
}
