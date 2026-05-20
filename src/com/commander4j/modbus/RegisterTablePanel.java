package com.commander4j.modbus;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;

import com.digitalpetri.modbus.server.ProcessImage.Modification.CoilModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.DiscreteInputModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.HoldingRegisterModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.InputRegisterModification;

/**
 * One tab of the server window: a live, editable grid for a single {@link RegisterKind}.
 *
 * <p>The visible address window is chosen with the Start / Count spinners. The grid is
 * refreshed from the process image both when the operator edits a cell and when a Modbus
 * client writes a value. Client-driven updates arrive on a Netty I/O thread; they are
 * buffered in {@link #dirtyAddresses} and applied to the table by a Swing timer so that
 * bursts of writes neither flood the event dispatch thread nor cancel an in-progress edit.
 */
public class RegisterTablePanel extends JPanel
{

	private static final long serialVersionUID = 1L;

	/** Upper bound on the number of rows shown at once, to keep the table responsive. */
	private static final int MAX_COUNT = 2000;

	/** Refresh cadence for applying buffered client writes, in milliseconds. */
	private static final int REFRESH_INTERVAL_MS = 75;

	private final RegisterKind kind;
	private final RegisterTableModel model;
	private final JTable table;

	private final JSpinner startSpinner;
	private final JSpinner countSpinner;

	/** Addresses changed since the last refresh tick; written from Netty I/O threads. */
	private final Set<Integer> dirtyAddresses = ConcurrentHashMap.newKeySet();

	public RegisterTablePanel(ServerController controller, RegisterKind kind)
	{
		super(new BorderLayout(6, 6));
		this.kind = kind;
		this.model = new RegisterTableModel(kind, controller);

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		startSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
		countSpinner = new JSpinner(new SpinnerNumberModel(64, 1, MAX_COUNT, 1));

		JButton applyButton = new JButton("Apply range");
		JButton zeroButton = new JButton("Zero range");

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		bar.add(new JLabel("Start address:"));
		bar.add(startSpinner);
		bar.add(new JLabel("Count:"));
		bar.add(countSpinner);
		bar.add(Box.createHorizontalStrut(8));
		bar.add(applyButton);
		bar.add(zeroButton);
		add(bar, BorderLayout.NORTH);

		table = new JTable(model);
		table.setRowHeight(22);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.getColumnModel().getColumn(0).setPreferredWidth(90);
		table.getColumnModel().getColumn(1).setPreferredWidth(110);
		add(new JScrollPane(table), BorderLayout.CENTER);

		applyButton.addActionListener(_ -> applyRange());
		zeroButton.addActionListener(_ ->
		{
			stopEditing();
			controller.zeroRange(kind, model.getStartAddress(), model.getCount());
			model.reloadSnapshot();
			model.fireAll();
		});

		controller.getProcessImage().addModificationListener(new ModificationAdapter()
		{
			@Override
			public void onCoilsModified(List<CoilModification> modifications)
			{
				if (kind == RegisterKind.COILS)
				{
					for (CoilModification m : modifications)
					{
						dirtyAddresses.add(m.address());
					}
				}
			}

			@Override
			public void onDiscreteInputsModified(List<DiscreteInputModification> modifications)
			{
				if (kind == RegisterKind.DISCRETE_INPUTS)
				{
					for (DiscreteInputModification m : modifications)
					{
						dirtyAddresses.add(m.address());
					}
				}
			}

			@Override
			public void onHoldingRegistersModified(List<HoldingRegisterModification> modifications)
			{
				if (kind == RegisterKind.HOLDING_REGISTERS)
				{
					for (HoldingRegisterModification m : modifications)
					{
						dirtyAddresses.add(m.address());
					}
				}
			}

			@Override
			public void onInputRegistersModified(List<InputRegisterModification> modifications)
			{
				if (kind == RegisterKind.INPUT_REGISTERS)
				{
					for (InputRegisterModification m : modifications)
					{
						dirtyAddresses.add(m.address());
					}
				}
			}
		});

		Timer refreshTimer = new Timer(REFRESH_INTERVAL_MS, _ -> drainDirtyAddresses());
		refreshTimer.start();
	}

	public RegisterKind getKind()
	{
		return kind;
	}

	private void applyRange()
	{
		stopEditing();
		int start = (Integer) startSpinner.getValue();
		int count = (Integer) countSpinner.getValue();
		dirtyAddresses.clear();
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
	 * Applies any addresses changed since the last tick. Runs on the EDT. The snapshot is
	 * re-read in one transaction; only the affected rows are repainted, and a row that is
	 * currently being edited is left alone so the operator's keystrokes are not lost.
	 */
	private void drainDirtyAddresses()
	{
		if (dirtyAddresses.isEmpty())
		{
			return;
		}
		List<Integer> changed = new ArrayList<>(dirtyAddresses);
		dirtyAddresses.clear();

		model.reloadSnapshot();

		int start = model.getStartAddress();
		int count = model.getCount();
		int editingRow = table.isEditing() ? table.getEditingRow() : -1;

		for (int address : changed)
		{
			int row = address - start;
			if (row < 0 || row >= count || row == editingRow)
			{
				continue;
			}
			model.fireRow(row);
		}
	}
}
