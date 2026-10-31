package com.runeroute;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Starts RuneLite with the plugin loaded. Run with: ./gradlew run */
public class RuneRoutePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RuneRoutePlugin.class);
		RuneLite.main(args);
	}
}
