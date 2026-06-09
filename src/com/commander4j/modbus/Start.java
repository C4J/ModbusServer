package com.commander4j.modbus;

import java.io.File;

/**
 * Entry point for util_modbusServer. The first command-line argument selects the run mode:
 *
 * <ul>
 *   <li>{@code GUI} (default when no argument is given) &mdash; show the Swing window
 *       {@link ServerFrame} so the operator can drive the server interactively.</li>
 *   <li>{@code Service} &mdash; load {@link ConfigStore#DEFAULT_FILE}, bind the Modbus
 *       server headlessly, attach a JVM shutdown hook, and block until the JVM is asked to
 *       stop (SIGTERM from install4j's service launcher, Ctrl-C on the console, or an OS
 *       shutdown).</li>
 * </ul>
 *
 * <p>This mirrors the pattern used by {@code c4j_middleware4j} and {@code c4j_messagesplitter4j}:
 * a single main class with two install4j launchers (GUI and Service) configured to pass the
 * matching argument.</p>
 */
public final class Start
{

	private Start()
	{
	}

	public static void main(String[] args)
	{
		String mode = (args.length >= 1 && !args[0].isBlank()) ? args[0].trim() : "GUI";

		switch (mode)
		{
			case "GUI"     -> runGui();
			case "Service" -> runService();
			default ->
			{
				System.err.println("Unknown run mode '" + mode + "'. Expected 'GUI' or 'Service'.");
				System.exit(1);
			}
		}
	}

	private static void runGui()
	{
		ModbusServerApp.main(new String[0]);
	}

	private static void runService()
	{
		File configFile = ConfigStore.DEFAULT_FILE;
		ServerConfig cfg;
		try
		{
			cfg = ConfigStore.load(configFile);
		}
		catch (Exception ex)
		{
			System.err.println("Service: failed to load " + configFile.getAbsolutePath() + ": " + ex.getMessage());
			System.exit(1);
			return;
		}

		System.out.println("Service: loaded " + configFile.getAbsolutePath());
		System.out.println("Service: bind " + cfg.bindAddress() + ":" + cfg.port() + ", unit ID " + cfg.unitId());

		ServerController controller = new ServerController();
		controller.setUnitId(cfg.unitId());

		// Hook is registered before start() so a failure between bind and the polling loop
		// still triggers an orderly stop.
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			System.out.println("Service: shutdown signal received.");
			try
			{
				controller.stop();
			}
			catch (Exception ex)
			{
				System.err.println("Service: error during stop: " + ex.getMessage());
			}
		}, "modbus-shutdown"));

		try
		{
			controller.start(cfg.bindAddress(), cfg.port());
		}
		catch (Exception ex)
		{
			System.err.println("Service: failed to start Modbus server: " + ex.getMessage());
			System.exit(1);
			return;
		}
		System.out.println("Service: Modbus server started.");

		// Park the main thread; the shutdown hook drives the actual stop and the JVM
		// terminates this thread once the hook returns.
		while (controller.isRunning())
		{
			try
			{
				Thread.sleep(1000);
			}
			catch (InterruptedException ie)
			{
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
