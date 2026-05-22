package com.commander4j.modbus;

import javax.swing.SwingUtilities;

import com.commander4j.util.JUtility;

/**
 * Entry point for the Commander4j Modbus TCP server simulator.
 *
 * <p>The application hosts a Modbus/TCP server (digitalpetri modbus 2.1.5) whose coils,
 * discrete inputs, holding registers and input registers are exposed in a Swing window.
 * Values written by a connected Modbus client are shown live, and the operator can input
 * or toggle any value directly to drive the connected client.
 */
public final class ModbusServerApp
{

	private ModbusServerApp()
	{
	}

	public static void main(String[] args)
	{
		JUtility.setLookAndFeel("Nimbus");

		SwingUtilities.invokeLater(() -> new ServerFrame().setVisible(true));
	}
}
