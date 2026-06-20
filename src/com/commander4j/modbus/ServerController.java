package com.commander4j.modbus;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import com.digitalpetri.modbus.server.ModbusServices;
import com.digitalpetri.modbus.server.ModbusTcpServer;
import com.digitalpetri.modbus.server.ProcessImage;
import com.digitalpetri.modbus.server.ReadWriteModbusServices;
import com.digitalpetri.modbus.tcp.server.NettyTcpServerTransport;

/**
 * Owns the Modbus/TCP server lifecycle and the single {@link ProcessImage} that backs
 * every register table.
 *
 * <p>The {@code ProcessImage} is created once and lives for the lifetime of the
 * application, so UI listeners can be attached to it before the server is ever started.
 * Starting and stopping the server only creates and tears down the Netty transport; a
 * fresh transport is built on every {@link #start} so the server can be restarted within
 * the same JVM.
 */
public class ServerController
{

	private static final byte[] EMPTY_REGISTER = new byte[2];

	private final ProcessImage image = new ProcessImage();
	private volatile int unitId = 0;

	/**
	 * Handles every Modbus read/write function code against {@link #image}. A request is
	 * served only when its unit ID matches the configured {@link #unitId}; any other unit
	 * ID is reported back to the client as an unknown unit. This is a deliberate test
	 * tool, so it emulates exactly one device.
	 */
	private final ModbusServices services = new ReadWriteModbusServices()
	{
		@Override
		protected Optional<ProcessImage> getProcessImage(int requestedUnitId)
		{
			return (requestedUnitId == unitId) ? Optional.of(image) : Optional.empty();
		}
	};

	private ModbusTcpServer server; // guarded by this
	private volatile boolean running;

	public ProcessImage getProcessImage()
	{
		return image;
	}

	public int getUnitId()
	{
		return unitId;
	}

	public void setUnitId(int unitId)
	{
		this.unitId = unitId;
	}

	public boolean isRunning()
	{
		return running;
	}

	/** Builds a fresh transport and binds the server. Blocks until the bind completes. */
	public synchronized void start(String bindAddress, int port) throws Exception
	{
		if (running)
		{
			return;
		}
		NettyTcpServerTransport transport = NettyTcpServerTransport.create(cfg ->
		{
			cfg.setBindAddress(bindAddress);
			cfg.setPort(port);
		});
		ModbusTcpServer started = ModbusTcpServer.create(transport, services);
		started.start();
		server = started;
		running = true;
	}

	/** Unbinds the server. The {@link ProcessImage} and its values are left untouched. */
	public synchronized void stop() throws Exception
	{
		if (!running)
		{
			return;
		}
		try
		{
			server.stop();
		}
		finally
		{
			server = null;
			running = false;
		}
	}

	// --- value access used by the table models -----------------------------------------

	/** Reads {@code count} bit values (coils or discrete inputs) starting at {@code start}. */
	public boolean[] readBits(RegisterKind kind, int start, int count)
	{
		boolean[] values = new boolean[count];
		Function<Map<Integer, Boolean>, Object> reader = map ->
		{
			for (int i = 0; i < count; i++)
			{
				values[i] = map.getOrDefault(start + i, Boolean.FALSE);
			}
			return null;
		};
		image.get(tx -> kind == RegisterKind.COILS ? tx.readCoils(reader) : tx.readDiscreteInputs(reader));
		return values;
	}

	/** Reads {@code count} register values (holding or input) starting at {@code start}. */
	public int[] readRegisters(RegisterKind kind, int start, int count)
	{
		int[] values = new int[count];
		Function<Map<Integer, byte[]>, Object> reader = map ->
		{
			for (int i = 0; i < count; i++)
			{
				values[i] = toInt(map.getOrDefault(start + i, EMPTY_REGISTER));
			}
			return null;
		};
		image.get(tx -> kind == RegisterKind.HOLDING_REGISTERS ? tx.readHoldingRegisters(reader) : tx.readInputRegisters(reader));
		return values;
	}

	/** Sets a single coil or discrete input. A {@code false} value clears the address. */
	public void writeBit(RegisterKind kind, int address, boolean value)
	{
		Consumer<Map<Integer, Boolean>> writer = map ->
		{
			if (value)
			{
				map.put(address, Boolean.TRUE);
			}
			else
			{
				map.remove(address);
			}
		};
		image.with(tx ->
		{
			if (kind == RegisterKind.COILS)
			{
				tx.writeCoils(writer);
			}
			else
			{
				tx.writeDiscreteInputs(writer);
			}
		});
	}

	/** Sets a single holding or input register. A zero value clears the address. */
	public void writeRegister(RegisterKind kind, int address, int value)
	{
		int masked = value & 0xFFFF;
		Consumer<Map<Integer, byte[]>> writer = map ->
		{
			if (masked == 0)
			{
				map.remove(address);
			}
			else
			{
				map.put(address, new byte[] { (byte) (masked >> 8), (byte) masked });
			}
		};
		image.with(tx ->
		{
			if (kind == RegisterKind.HOLDING_REGISTERS)
			{
				tx.writeHoldingRegisters(writer);
			}
			else
			{
				tx.writeInputRegisters(writer);
			}
		});
	}

	/** Resets every value in the given range to zero / false in a single transaction. */
	public void zeroRange(RegisterKind kind, int start, int count)
	{
		image.with(tx ->
		{
			switch (kind)
			{
				case COILS -> tx.writeCoils(map -> removeRange(map, start, count));
				case DISCRETE_INPUTS -> tx.writeDiscreteInputs(map -> removeRange(map, start, count));
				case HOLDING_REGISTERS -> tx.writeHoldingRegisters(map -> removeRange(map, start, count));
				case INPUT_REGISTERS -> tx.writeInputRegisters(map -> removeRange(map, start, count));
			}
		});
	}

	private static void removeRange(Map<Integer, ?> map, int start, int count)
	{
		for (int i = 0; i < count; i++)
		{
			map.remove(start + i);
		}
	}

	private static int toInt(byte[] register)
	{
		if (register == null || register.length < 2)
		{
			return 0;
		}
		return ((register[0] & 0xFF) << 8) | (register[1] & 0xFF);
	}
}
