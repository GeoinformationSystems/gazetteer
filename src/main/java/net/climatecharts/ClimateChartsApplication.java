package net.climatecharts;

import java.util.HashSet;
import java.util.Set;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Application endpoint for ClimateCharts server
 * 
 * @author matthias
 */
@ApplicationPath("/")
public class ClimateChartsApplication extends Application
{	
	@Override
	public Set<Class<?>> getClasses()
	{
		final Set<Class<?>> classes = new HashSet<>();
		classes.add(Gazetteer.class);
		return classes;
	}
}
