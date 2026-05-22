package com.commander4j.modbus;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

import com.digitalpetri.modbus.server.ProcessImage.Modification.CoilModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.DiscreteInputModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.HoldingRegisterModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.InputRegisterModification;

/**
 * Single-table view of all four Modbus register spaces, aligned by zero-based
 * protocol address. A shared Start/Count range drives coils, discrete inputs,
 * input registers and holding registers; client-driven changes from any kind
 * are buffered per-kind and drained together on the EDT every 75 ms so bursts
 * of writes neither flood the event dispatch thread nor cancel an in-progress
 * operator edit.
 */
public class UnifiedRegisterPanel extends JPanel
{

	private static final long serialVersionUID = 1L;

	/** Upper bound on the number of rows shown at once, to keep the table responsive. */
	private static final int MAX_COUNT = 2000;

	/** Refresh cadence for applying buffered client writes, in milliseconds. */
	private static final int REFRESH_INTERVAL_MS = 75;

	private static final Color ADDRESS_BG = new Color(155, 77, 133);
	private static final Color ADDRESS_FG = Color.WHITE;
	private static final Color COIL_BG = new Color(168, 208, 141);
	private static final Color DISC_BG = new Color(157, 183, 224);
	private static final Color INPUT_BG = new Color(245, 240, 161);
	private static final Color HOLD_BG = new Color(244, 167, 143);
	private static final Color GROUP_FG = Color.BLACK;

	private final UnifiedRegisterTableModel model;
	private final JTable table;

	private final JSpinner startSpinner;
	private final JSpinner countSpinner;

	/** Addresses changed since the last refresh tick; written from Netty I/O threads. */
	private final Map<RegisterKind, Set<Integer>> dirty = new EnumMap<>(RegisterKind.class);

	public UnifiedRegisterPanel(ServerController controller)
	{
		super(new BorderLayout(6, 6));
		for (RegisterKind k : RegisterKind.values())
		{
			dirty.put(k, ConcurrentHashMap.newKeySet());
		}

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		model = new UnifiedRegisterTableModel(controller);

		startSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
		countSpinner = new JSpinner(new SpinnerNumberModel(64, 1, MAX_COUNT, 1));

		JButton applyButton = new JButton("Apply range");
		JButton zeroButton = new JButton("Zero…");

		JPopupMenu zeroMenu = new JPopupMenu();
		zeroMenu.add(zeroItem(controller, "Zero Coils", RegisterKind.COILS));
		zeroMenu.add(zeroItem(controller, "Zero Discrete Inputs", RegisterKind.DISCRETE_INPUTS));
		zeroMenu.add(zeroItem(controller, "Zero Input Registers", RegisterKind.INPUT_REGISTERS));
		zeroMenu.add(zeroItem(controller, "Zero Holding Registers", RegisterKind.HOLDING_REGISTERS));
		zeroMenu.addSeparator();
		zeroMenu.add(zeroAllItem(controller));
		zeroButton.addActionListener(_ -> zeroMenu.show(zeroButton, 0, zeroButton.getHeight()));

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		bar.add(new JLabel("Start address:"));
		bar.add(startSpinner);
		bar.add(new JLabel("Count:"));
		bar.add(countSpinner);
		bar.add(Box.createHorizontalStrut(8));
		bar.add(applyButton);
		bar.add(zeroButton);
		add(bar, BorderLayout.NORTH);

		List<ColumnGroup> groups = List.of(
				new ColumnGroup("", ADDRESS_BG, ADDRESS_FG,
						UnifiedRegisterTableModel.COL_ADDRESS, UnifiedRegisterTableModel.COL_ADDRESS),
				new ColumnGroup("Coil", COIL_BG, GROUP_FG,
						UnifiedRegisterTableModel.COL_COIL_REF, UnifiedRegisterTableModel.COL_COIL_VAL),
				new ColumnGroup("Discrete", DISC_BG, GROUP_FG,
						UnifiedRegisterTableModel.COL_DISC_REF, UnifiedRegisterTableModel.COL_DISC_VAL),
				new ColumnGroup("Input", INPUT_BG, GROUP_FG,
						UnifiedRegisterTableModel.COL_INPUT_REF, UnifiedRegisterTableModel.COL_INPUT_HEX),
				new ColumnGroup("Holding", HOLD_BG, GROUP_FG,
						UnifiedRegisterTableModel.COL_HOLD_REF, UnifiedRegisterTableModel.COL_HOLD_HEX));

		table = new JTable(model)
		{
			private static final long serialVersionUID = 1L;

			@Override
			protected JTableHeader createDefaultTableHeader()
			{
				return new GroupableTableHeader(getColumnModel(), groups);
			}
		};
		table.setRowHeight(22);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.setShowGrid(true);
		Color gridColour = UIManager.getColor("Table.gridColor");
		if (gridColour != null)
		{
			table.setGridColor(gridColour);
		}

		configureColumnWidths();

		ColourCellRenderer textRenderer = new ColourCellRenderer(groups);
		BooleanCellRenderer boolRenderer = new BooleanCellRenderer(groups);
		for (int i = 0; i < table.getColumnCount(); i++)
		{
			TableColumn col = table.getColumnModel().getColumn(i);
			if (model.getColumnClass(i) == Boolean.class)
			{
				col.setCellRenderer(boolRenderer);
			}
			else
			{
				col.setCellRenderer(textRenderer);
			}
		}
		table.getTableHeader().setDefaultRenderer(new GroupAwareHeaderRenderer(groups));
		table.getTableHeader().setReorderingAllowed(false);

		add(new JScrollPane(table), BorderLayout.CENTER);

		applyButton.addActionListener(_ -> applyRange());

		controller.getProcessImage().addModificationListener(new ModificationAdapter()
		{
			@Override
			public void onCoilsModified(List<CoilModification> mods)
			{
				Set<Integer> set = dirty.get(RegisterKind.COILS);
				for (CoilModification m : mods)
				{
					set.add(m.address());
				}
			}

			@Override
			public void onDiscreteInputsModified(List<DiscreteInputModification> mods)
			{
				Set<Integer> set = dirty.get(RegisterKind.DISCRETE_INPUTS);
				for (DiscreteInputModification m : mods)
				{
					set.add(m.address());
				}
			}

			@Override
			public void onInputRegistersModified(List<InputRegisterModification> mods)
			{
				Set<Integer> set = dirty.get(RegisterKind.INPUT_REGISTERS);
				for (InputRegisterModification m : mods)
				{
					set.add(m.address());
				}
			}

			@Override
			public void onHoldingRegistersModified(List<HoldingRegisterModification> mods)
			{
				Set<Integer> set = dirty.get(RegisterKind.HOLDING_REGISTERS);
				for (HoldingRegisterModification m : mods)
				{
					set.add(m.address());
				}
			}
		});

		Timer refreshTimer = new Timer(REFRESH_INTERVAL_MS, _ -> drainDirty());
		refreshTimer.start();
	}

	private JMenuItem zeroItem(ServerController controller, String label, RegisterKind kind)
	{
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(_ ->
		{
			stopEditing();
			controller.zeroRange(kind, model.getStartAddress(), model.getCount());
			model.reloadSnapshot();
			model.fireTableDataChanged();
		});
		return item;
	}

	private JMenuItem zeroAllItem(ServerController controller)
	{
		JMenuItem item = new JMenuItem("Zero All");
		item.addActionListener(_ ->
		{
			stopEditing();
			for (RegisterKind k : RegisterKind.values())
			{
				controller.zeroRange(k, model.getStartAddress(), model.getCount());
			}
			model.reloadSnapshot();
			model.fireTableDataChanged();
		});
		return item;
	}

	private void configureColumnWidths()
	{
		setColumnWidth(UnifiedRegisterTableModel.COL_ADDRESS, 75);
		setColumnWidth(UnifiedRegisterTableModel.COL_COIL_REF, 95);
		setColumnWidth(UnifiedRegisterTableModel.COL_COIL_VAL, 60);
		setColumnWidth(UnifiedRegisterTableModel.COL_DISC_REF, 95);
		setColumnWidth(UnifiedRegisterTableModel.COL_DISC_VAL, 60);
		setColumnWidth(UnifiedRegisterTableModel.COL_INPUT_REF, 95);
		setColumnWidth(UnifiedRegisterTableModel.COL_INPUT_VAL, 90);
		setColumnWidth(UnifiedRegisterTableModel.COL_INPUT_HEX, 80);
		setColumnWidth(UnifiedRegisterTableModel.COL_HOLD_REF, 95);
		setColumnWidth(UnifiedRegisterTableModel.COL_HOLD_VAL, 90);
		setColumnWidth(UnifiedRegisterTableModel.COL_HOLD_HEX, 80);
	}

	private void setColumnWidth(int col, int width)
	{
		TableColumn c = table.getColumnModel().getColumn(col);
		c.setMinWidth(40);
		c.setPreferredWidth(width);
	}

	private void applyRange()
	{
		stopEditing();
		int start = (Integer) startSpinner.getValue();
		int count = (Integer) countSpinner.getValue();
		for (Set<Integer> set : dirty.values())
		{
			set.clear();
		}
		model.setRange(start, count);
	}

	private void stopEditing()
	{
		if (table.isEditing())
		{
			table.getCellEditor().stopCellEditing();
		}
	}

	/**
	 * Applies any addresses changed since the last tick across all four kinds.
	 * Runs on the EDT. Every snapshot is re-read in one batch; only the affected
	 * rows are repainted, and a row currently being edited is left alone so the
	 * operator's keystrokes are not lost.
	 */
	private void drainDirty()
	{
		Map<RegisterKind, List<Integer>> snap = new EnumMap<>(RegisterKind.class);
		boolean any = false;
		for (RegisterKind k : RegisterKind.values())
		{
			Set<Integer> set = dirty.get(k);
			if (set.isEmpty())
			{
				snap.put(k, List.of());
				continue;
			}
			List<Integer> list = new ArrayList<>(set);
			set.clear();
			snap.put(k, list);
			any = true;
		}
		if (!any)
		{
			return;
		}

		model.reloadSnapshot();

		int start = model.getStartAddress();
		int count = model.getCount();
		int editingRow = table.isEditing() ? table.getEditingRow() : -1;

		Set<Integer> changedRows = new HashSet<>();
		for (List<Integer> addrs : snap.values())
		{
			for (int addr : addrs)
			{
				int row = addr - start;
				if (row >= 0 && row < count && row != editingRow)
				{
					changedRows.add(row);
				}
			}
		}
		for (int row : changedRows)
		{
			model.fireRow(row);
		}
	}

	// ---- Cell renderers ---------------------------------------------------

	/** Text/number cell renderer that fills the background with the column-group colour. */
	private static final class ColourCellRenderer extends DefaultTableCellRenderer
	{
		private static final long serialVersionUID = 1L;

		private final List<ColumnGroup> groups;

		ColourCellRenderer(List<ColumnGroup> groups)
		{
			this.groups = groups;
			setHorizontalAlignment(CENTER);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column)
		{
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			ColumnGroup g = groupFor(column);
			if (isSelected)
			{
				setBackground(table.getSelectionBackground());
				setForeground(table.getSelectionForeground());
			}
			else if (g != null)
			{
				setBackground(g.colour());
				setForeground(g.textColour());
			}
			return this;
		}

		private ColumnGroup groupFor(int column)
		{
			for (ColumnGroup g : groups)
			{
				if (g.contains(column))
				{
					return g;
				}
			}
			return null;
		}
	}

	/**
	 * Boolean cell renderer that paints its own background so the checkbox sits
	 * on the column-group colour rather than Nimbus's stripe colour. Same
	 * pattern as the prior per-tab renderer, adapted to the group palette.
	 */
	private static final class BooleanCellRenderer extends JCheckBox implements TableCellRenderer
	{
		private static final long serialVersionUID = 1L;

		private final List<ColumnGroup> groups;
		private Color cellBackground = Color.WHITE;

		BooleanCellRenderer(List<ColumnGroup> groups)
		{
			this.groups = groups;
			setHorizontalAlignment(SwingConstants.CENTER);
			setBorderPainted(false);
			setOpaque(false);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column)
		{
			setSelected(Boolean.TRUE.equals(value));
			if (isSelected)
			{
				cellBackground = table.getSelectionBackground();
				setForeground(table.getSelectionForeground());
			}
			else
			{
				ColumnGroup g = groupFor(column);
				cellBackground = g != null ? g.colour() : Color.WHITE;
				setForeground(g != null ? g.textColour() : Color.BLACK);
			}
			return this;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(cellBackground);
			g.fillRect(0, 0, getWidth(), getHeight());
			super.paintComponent(g);
		}

		private ColumnGroup groupFor(int column)
		{
			for (ColumnGroup grp : groups)
			{
				if (grp.contains(column))
				{
					return grp;
				}
			}
			return null;
		}
	}

	/** Sub-column header renderer that paints the column-group colour behind the label. */
	private static final class GroupAwareHeaderRenderer extends DefaultTableCellRenderer
	{
		private static final long serialVersionUID = 1L;

		private final List<ColumnGroup> groups;

		GroupAwareHeaderRenderer(List<ColumnGroup> groups)
		{
			this.groups = groups;
			setHorizontalAlignment(CENTER);
			setOpaque(true);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column)
		{
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			ColumnGroup g = groupFor(column);
			if (g != null)
			{
				setBackground(g.colour());
				setForeground(g.textColour());
			}
			setFont(getFont().deriveFont(Font.BOLD));
			Color grid = UIManager.getColor("Table.gridColor");
			setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, grid != null ? grid : Color.GRAY));
			return this;
		}

		private ColumnGroup groupFor(int column)
		{
			for (ColumnGroup g : groups)
			{
				if (g.contains(column))
				{
					return g;
				}
			}
			return null;
		}
	}
}
