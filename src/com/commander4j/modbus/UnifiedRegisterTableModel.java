package com.commander4j.modbus;

import javax.swing.table.AbstractTableModel;

/**
 * Single Swing table model that exposes all four Modbus data tables aligned by
 * a shared zero-based address. Holds parallel snapshots of a contiguous address
 * window read from the {@link ServerController}'s process image:
 *
 * <ul>
 *   <li>Coil bit + conventional reference (1..)</li>
 *   <li>Discrete input bit + conventional reference (10001..)</li>
 *   <li>Input register + reference (30001..) + hex view</li>
 *   <li>Holding register + reference (40001..) + hex view</li>
 * </ul>
 *
 * Editing a Value column writes straight through to the process image.
 */
public class UnifiedRegisterTableModel extends AbstractTableModel
{

	private static final long serialVersionUID = 1L;

	public static final int COL_ADDRESS = 0;
	public static final int COL_COIL_REF = 1;
	public static final int COL_COIL_VAL = 2;
	public static final int COL_DISC_REF = 3;
	public static final int COL_DISC_VAL = 4;
	public static final int COL_INPUT_REF = 5;
	public static final int COL_INPUT_VAL = 6;
	public static final int COL_INPUT_HEX = 7;
	public static final int COL_HOLD_REF = 8;
	public static final int COL_HOLD_VAL = 9;
	public static final int COL_HOLD_HEX = 10;

	private static final int COLUMN_COUNT = 11;

	private final ServerController controller;

	private int startAddress;
	private int count;

	private boolean[] coils = new boolean[0];
	private boolean[] discrete = new boolean[0];
	private int[] input = new int[0];
	private int[] holding = new int[0];

	public UnifiedRegisterTableModel(ServerController controller)
	{
		this.controller = controller;
		this.startAddress = 0;
		this.count = 64;
		reloadSnapshot();
	}

	public int getStartAddress()
	{
		return startAddress;
	}

	public int getCount()
	{
		return count;
	}

	/** Changes the visible address window and refreshes every snapshot. */
	public void setRange(int startAddress, int count)
	{
		this.startAddress = startAddress;
		this.count = count;
		reloadSnapshot();
		fireTableDataChanged();
	}

	/** Re-reads every snapshot from the process image. Must be called on the EDT. */
	public void reloadSnapshot()
	{
		coils = controller.readBits(RegisterKind.COILS, startAddress, count);
		discrete = controller.readBits(RegisterKind.DISCRETE_INPUTS, startAddress, count);
		input = controller.readRegisters(RegisterKind.INPUT_REGISTERS, startAddress, count);
		holding = controller.readRegisters(RegisterKind.HOLDING_REGISTERS, startAddress, count);
	}

	/** Repaints a single row without disturbing editors on other rows. */
	public void fireRow(int row)
	{
		fireTableRowsUpdated(row, row);
	}

	@Override
	public int getRowCount()
	{
		return count;
	}

	@Override
	public int getColumnCount()
	{
		return COLUMN_COUNT;
	}

	@Override
	public String getColumnName(int column)
	{
		return switch (column)
		{
			case COL_ADDRESS -> "Address";
			case COL_COIL_REF, COL_DISC_REF, COL_INPUT_REF, COL_HOLD_REF -> "Modbus Ref";
			case COL_COIL_VAL, COL_DISC_VAL, COL_INPUT_VAL, COL_HOLD_VAL -> "Value";
			case COL_INPUT_HEX, COL_HOLD_HEX -> "Hex";
			default -> "";
		};
	}

	@Override
	public Class<?> getColumnClass(int column)
	{
		return switch (column)
		{
			case COL_COIL_VAL, COL_DISC_VAL -> Boolean.class;
			case COL_INPUT_HEX, COL_HOLD_HEX -> String.class;
			default -> Integer.class;
		};
	}

	@Override
	public boolean isCellEditable(int row, int column)
	{
		return column == COL_COIL_VAL
				|| column == COL_DISC_VAL
				|| column == COL_INPUT_VAL
				|| column == COL_HOLD_VAL;
	}

	@Override
	public Object getValueAt(int row, int column)
	{
		int address = startAddress + row;
		return switch (column)
		{
			case COL_ADDRESS -> address;
			case COL_COIL_REF -> RegisterKind.COILS.conventionalBase + address;
			case COL_COIL_VAL -> coils[row];
			case COL_DISC_REF -> RegisterKind.DISCRETE_INPUTS.conventionalBase + address;
			case COL_DISC_VAL -> discrete[row];
			case COL_INPUT_REF -> RegisterKind.INPUT_REGISTERS.conventionalBase + address;
			case COL_INPUT_VAL -> input[row];
			case COL_INPUT_HEX -> String.format("0x%04X", input[row] & 0xFFFF);
			case COL_HOLD_REF -> RegisterKind.HOLDING_REGISTERS.conventionalBase + address;
			case COL_HOLD_VAL -> holding[row];
			case COL_HOLD_HEX -> String.format("0x%04X", holding[row] & 0xFFFF);
			default -> null;
		};
	}

	@Override
	public void setValueAt(Object value, int row, int column)
	{
		if (!isCellEditable(row, column))
		{
			return;
		}
		int address = startAddress + row;
		switch (column)
		{
			case COL_COIL_VAL ->
			{
				boolean v = Boolean.TRUE.equals(value);
				controller.writeBit(RegisterKind.COILS, address, v);
				coils[row] = v;
			}
			case COL_DISC_VAL ->
			{
				boolean v = Boolean.TRUE.equals(value);
				controller.writeBit(RegisterKind.DISCRETE_INPUTS, address, v);
				discrete[row] = v;
			}
			case COL_INPUT_VAL ->
			{
				Integer parsed = parseRegister(value);
				if (parsed == null) return;
				controller.writeRegister(RegisterKind.INPUT_REGISTERS, address, parsed);
				input[row] = parsed;
			}
			case COL_HOLD_VAL ->
			{
				Integer parsed = parseRegister(value);
				if (parsed == null) return;
				controller.writeRegister(RegisterKind.HOLDING_REGISTERS, address, parsed);
				holding[row] = parsed;
			}
			default ->
			{
				return;
			}
		}
		fireTableRowsUpdated(row, row);
	}

	private static Integer parseRegister(Object value)
	{
		try
		{
			int n = ((Number) value).intValue();
			return Math.max(0, Math.min(0xFFFF, n));
		}
		catch (RuntimeException notANumber)
		{
			return null;
		}
	}
}
