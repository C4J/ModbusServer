package com.commander4j.modbus;

import javax.swing.table.AbstractTableModel;

/**
 * Swing table model for one {@link RegisterKind}. Holds a snapshot of a contiguous address
 * window read from the {@link ServerController}'s process image; {@link #reloadSnapshot()}
 * refreshes that snapshot.
 *
 * <p>Columns: address (zero-based protocol address), the conventional Modbus reference, the
 * editable value, and - for register tables only - a read-only hexadecimal view. Only the
 * value column is editable; editing it writes straight through to the process image.
 */
public class RegisterTableModel extends AbstractTableModel
{

	private static final long serialVersionUID = 1L;

	private static final int COL_ADDRESS = 0;
	private static final int COL_REFERENCE = 1;
	private static final int COL_VALUE = 2;
	private static final int COL_HEX = 3;

	private final RegisterKind kind;
	private final ServerController controller;

	private int startAddress;
	private int count;

	private boolean[] bitValues = new boolean[0];
	private int[] registerValues = new int[0];

	public RegisterTableModel(RegisterKind kind, ServerController controller)
	{
		this.kind = kind;
		this.controller = controller;
		this.startAddress = 0;
		this.count = 64;
		reloadSnapshot();
	}

	public RegisterKind getKind()
	{
		return kind;
	}

	public int getStartAddress()
	{
		return startAddress;
	}

	public int getCount()
	{
		return count;
	}

	/** Changes the visible address window and refreshes the table. */
	public void setRange(int startAddress, int count)
	{
		this.startAddress = startAddress;
		this.count = count;
		reloadSnapshot();
		fireTableDataChanged();
	}

	/** Re-reads the visible range from the process image. Must be called on the EDT. */
	public void reloadSnapshot()
	{
		if (kind.bit)
		{
			bitValues = controller.readBits(kind, startAddress, count);
		}
		else
		{
			registerValues = controller.readRegisters(kind, startAddress, count);
		}
	}

	/** Repaints a single row without disturbing editors on other rows. */
	public void fireRow(int row)
	{
		fireTableRowsUpdated(row, row);
	}

	/** Repaints every row. */
	public void fireAll()
	{
		fireTableDataChanged();
	}

	@Override
	public int getRowCount()
	{
		return count;
	}

	@Override
	public int getColumnCount()
	{
		return kind.bit ? 3 : 4;
	}

	@Override
	public String getColumnName(int column)
	{
		return switch (column)
		{
			case COL_ADDRESS -> "Address";
			case COL_REFERENCE -> "Modbus Ref";
			case COL_VALUE -> "Value";
			default -> "Hex";
		};
	}

	@Override
	public Class<?> getColumnClass(int column)
	{
		if (column == COL_VALUE)
		{
			return kind.bit ? Boolean.class : Integer.class;
		}
		if (column == COL_HEX)
		{
			return String.class;
		}
		return Integer.class;
	}

	@Override
	public boolean isCellEditable(int row, int column)
	{
		return column == COL_VALUE;
	}

	@Override
	public Object getValueAt(int row, int column)
	{
		int address = startAddress + row;
		return switch (column)
		{
			case COL_ADDRESS -> address;
			case COL_REFERENCE -> kind.conventionalBase + address;
			case COL_VALUE -> kind.bit ? (Object) bitValues[row] : (Object) registerValues[row];
			case COL_HEX -> String.format("0x%04X", registerValues[row] & 0xFFFF);
			default -> null;
		};
	}

	@Override
	public void setValueAt(Object value, int row, int column)
	{
		if (column != COL_VALUE)
		{
			return;
		}
		int address = startAddress + row;
		if (kind.bit)
		{
			boolean enabled = Boolean.TRUE.equals(value);
			controller.writeBit(kind, address, enabled);
			bitValues[row] = enabled;
		}
		else
		{
			int parsed;
			try
			{
				parsed = ((Number) value).intValue();
			}
			catch (RuntimeException notANumber)
			{
				return;
			}
			parsed = Math.max(0, Math.min(0xFFFF, parsed));
			controller.writeRegister(kind, address, parsed);
			registerValues[row] = parsed;
		}
		fireTableRowsUpdated(row, row);
	}
}
