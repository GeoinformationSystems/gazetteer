package net.climatecharts;

import java.io.IOException;

import org.apache.http.ParseException;
import org.junit.Test;

public class GazetteerTest
{
	// Test: Timmendorfer Strand, Travemünde, Lübeck, Schleswig-Holstein, Deutschland
//	double testLat = 53.9572;
//	double testLng = 10.8642;
	
	// Test: Dresden, Sachsen, Deutschland
//	double testLat = 51.0516;
//	double testLng = 13.7349;

	// Test: Spremberg, Brandenburg, Deutschland
//	double testLat = 51.5689;
//	double testLng = 14.3694;
	
	// Test: Hoyerswerda, Sachsen, Deutschland
//	double testLat = 51.4415;
//	double testLng = 14.2518;
	
	// Test: Weißwasser, Sachsen, Deutschland
//	double testLat = 51.5026;
//	double testLng = 14.6393;
	
	// Test: Roma, Italia / Vatican City
//	double testLat = 41.9139;
//	double testLng = 12.4388;
	
	// Test: Ostrava
//	double testLat = 49.8506;
//	double testLng = 18.3338;	
	
	// Test: Rabenau, Sachsen, Deutschland
	double testLat = 50.964;
	double testLng = 13.645;

        
	@Test
	public void testElevation() throws ParseException, IOException
	{
		String responseString = new Gazetteer().find("getElevation", testLat, testLng, 20);
		System.out.println(responseString);	
	}
	
	@Test
	public void testPlaceName() throws ParseException, IOException
	{
		String responseString = new Gazetteer().find("getName", testLat, testLng, 20);
		System.out.println(responseString);	
	}
}
