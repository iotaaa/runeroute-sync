package com.runeroute;

import java.io.InputStream;
import java.util.Properties;

/** Values baked in at build time (see processResources in build.gradle). */
final class BuildInfo
{
	static final String DEFAULT_URL = load("defaultUrl", "https://runeroute.app");

	private BuildInfo()
	{
	}

	private static String load(String key, String fallback)
	{
		try (InputStream in = BuildInfo.class.getResourceAsStream("build.properties"))
		{
			if (in == null)
			{
				return fallback;
			}
			Properties props = new Properties();
			props.load(in);
			String value = props.getProperty(key, "").trim();
			return value.isEmpty() || value.startsWith("$") ? fallback : value;
		}
		catch (Exception e)
		{
			return fallback;
		}
	}
}
