package com.commander4j.modbus;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

import com.commander4j.dialog.JDialogAbout;
import com.commander4j.dialog.JDialogLicenses;
import com.commander4j.gui.JButton4j;
import com.commander4j.gui.JLabel4j_std;
import com.commander4j.gui.JToggleButton4j;
import com.commander4j.sys.Common;
import com.commander4j.util.JHelp;
import com.commander4j.util.JUtility;
import com.digitalpetri.modbus.server.ProcessImage.Modification.CoilModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.DiscreteInputModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.HoldingRegisterModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.InputRegisterModification;

/**
 * Main application window: a connection bar, a single unified register table covering all
 * four Modbus data spaces (coils, discrete inputs, input registers, holding registers)
 * aligned by address, and an activity log. The window owns a single {@link ServerController};
 * starting and stopping the server is done on a background thread so the bind/unbind call
 * never blocks the event dispatch thread.
 */
public class ServerFrame extends JFrame
{

	private static final long serialVersionUID = 1L;

	private static final Color RUNNING_COLOR = new Color(0, 140, 0);
	private static final int MAX_LOG_LINES = 400;

	private final ServerController controller = new ServerController();

	private final JTextField bindField = new JTextField("0.0.0.0", 12);
	private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(502, 1, 65535, 1));
	private final JSpinner unitSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 247, 1));
	private final JToggleButton4j serverToggle = new JToggleButton4j(Common.icon_disconnected);
	private final JLabel4j_std statusLabel = new JLabel4j_std("Stopped");

	private final JTextArea logArea = new JTextArea(8, 80);
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

	public ServerFrame()
	{
		super(Common.buildTitle(null));
		JUtility.setLookAndFeel("Nimbus");
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

		add(buildConnectionBar(), BorderLayout.NORTH);

		UnifiedRegisterPanel registerPanel = new UnifiedRegisterPanel(controller);

		logArea.setEditable(false);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JScrollPane logScroll = new JScrollPane(logArea);
		logScroll.setBorder(BorderFactory.createTitledBorder("Activity"));

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, registerPanel, logScroll);
		split.setResizeWeight(0.78);
		add(split, BorderLayout.CENTER);

		add(buildRightToolBar(), BorderLayout.EAST);

		serverToggle.setToolTipText("Start Server");
		serverToggle.setPreferredSize(new Dimension(36, 36));
		serverToggle.setMaximumSize(new Dimension(36, 36));
		serverToggle.addActionListener(_ -> toggleServer());

		controller.getProcessImage().addModificationListener(new LoggingListener());

		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				confirmExit();
			}
		});

		setSize(1020, 860);
		centreOnActiveMonitor();
		log("Ready. Set the bind address and port, then press Start.");
		log("Coils and registers can be edited at any time, before or while the server runs.");
	}

	/**
	 * Centre the window on the monitor that currently holds the mouse pointer, falling back
	 * to {@link #setLocationRelativeTo} centring if the screen layout cannot be queried.
	 */
	private void centreOnActiveMonitor()
	{
		try
		{
			Point mouseLocation = MouseInfo.getPointerInfo().getLocation();
			GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

			GraphicsDevice activeDevice = ge.getDefaultScreenDevice();
			for (GraphicsDevice device : ge.getScreenDevices())
			{
				if (device.getDefaultConfiguration().getBounds().contains(mouseLocation))
				{
					activeDevice = device;
					break;
				}
			}

			GraphicsConfiguration gc = activeDevice.getDefaultConfiguration();
			Rectangle screenBounds = gc.getBounds();

			setLocation(screenBounds.x + (screenBounds.width - getWidth()) / 2,
					screenBounds.y + (screenBounds.height - getHeight()) / 2);
		}
		catch (HeadlessException | NullPointerException ex)
		{
			setLocationRelativeTo(null);
		}
	}

	private JPanel buildConnectionBar()
	{
		portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#"));
		unitSpinner.setEditor(new JSpinner.NumberEditor(unitSpinner, "#"));
		statusLabel.setForeground(Color.GRAY);

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
		bar.add(new JLabel4j_std("Bind address:"));
		bar.add(bindField);
		bar.add(new JLabel4j_std("Port:"));
		bar.add(portSpinner);
		bar.add(new JLabel4j_std("Unit ID:"));
		bar.add(unitSpinner);
		bar.add(serverToggle);
		bar.add(Box.createHorizontalStrut(12));
		bar.add(new JLabel4j_std("Status:"));
		bar.add(statusLabel);
		return bar;
	}

	private JToolBar buildRightToolBar()
	{
		JToolBar tb = new JToolBar(JToolBar.VERTICAL);
		tb.setFloatable(false);
		tb.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

		tb.add(iconButton(Common.icon_about,   "About",    _ -> new JDialogAbout().setVisible(true)));
		tb.add(iconButton(Common.icon_license, "Licences", _ -> new JDialogLicenses(ServerFrame.this).setVisible(true)));

		JButton4j btnHelp = new JButton4j(Common.icon_help);
		btnHelp.setToolTipText("Help");
		btnHelp.setPreferredSize(new Dimension(36, 36));
		btnHelp.setMaximumSize(new Dimension(36, 36));
		new JHelp().enableHelpOnButton(btnHelp, Common.helpURL);
		tb.add(btnHelp);

		tb.add(iconButton(Common.icon_exit, "Close", _ -> confirmExit()));

		return tb;
	}

	private static JButton4j iconButton(javax.swing.ImageIcon icon, String tooltip, java.awt.event.ActionListener a)
	{
		JButton4j b = new JButton4j(icon);
		b.setToolTipText(tooltip);
		b.setPreferredSize(new Dimension(36, 36));
		b.setMaximumSize(new Dimension(36, 36));
		b.addActionListener(a);
		return b;
	}

	private void toggleServer()
	{
		if (controller.isRunning())
		{
			stopServer();
		}
		else
		{
			startServer();
		}
	}

	private void startServer()
	{
		String host = bindField.getText().trim();
		if (host.isEmpty())
		{
			host = "0.0.0.0";
		}
		final int port = (Integer) portSpinner.getValue();
		final int unitId = (Integer) unitSpinner.getValue();
		final String bindHost = host;

		setConfigEnabled(false);
		serverToggle.setEnabled(false);
		controller.setUnitId(unitId);
		log("Starting server on " + bindHost + ":" + port + " (unit ID " + unitId + ")...");

		new Thread(() ->
		{
			try
			{
				controller.start(bindHost, port);
				SwingUtilities.invokeLater(() ->
				{
					statusLabel.setText("Running on " + bindHost + ":" + port + "  (unit ID " + unitId + ")");
					statusLabel.setForeground(RUNNING_COLOR);
					serverToggle.setIcon(Common.icon_connected);
					serverToggle.setToolTipText("Stop Server");
					serverToggle.setSelected(true);
					serverToggle.setEnabled(true);
					log("Server started. Waiting for client connections.");
				});
			}
			catch (Exception ex)
			{
				SwingUtilities.invokeLater(() ->
				{
					String reason = describe(ex);
					log("FAILED to start server: " + reason);
					statusLabel.setText("Stopped");
					statusLabel.setForeground(Color.GRAY);
					setConfigEnabled(true);
					serverToggle.setIcon(Common.icon_disconnected);
					serverToggle.setToolTipText("Start Server");
					serverToggle.setSelected(false);
					serverToggle.setEnabled(true);
					JOptionPane.showMessageDialog(ServerFrame.this, "Could not start the Modbus server:\n\n" + reason, "Modbus Server", JOptionPane.ERROR_MESSAGE);
				});
			}
		}, "modbus-server-start").start();
	}

	private void stopServer()
	{
		serverToggle.setEnabled(false);
		log("Stopping server...");

		new Thread(() ->
		{
			try
			{
				controller.stop();
			}
			catch (Exception ex)
			{
				SwingUtilities.invokeLater(() -> log("Error while stopping: " + describe(ex)));
			}
			SwingUtilities.invokeLater(() ->
			{
				statusLabel.setText("Stopped");
				statusLabel.setForeground(Color.GRAY);
				setConfigEnabled(true);
				serverToggle.setIcon(Common.icon_disconnected);
				serverToggle.setToolTipText("Start Server");
				serverToggle.setSelected(false);
				serverToggle.setEnabled(true);
				log("Server stopped.");
			});
		}, "modbus-server-stop").start();
	}

	private void confirmExit()
	{
		int choice = JOptionPane.showConfirmDialog(
				ServerFrame.this,
				"Are you sure you want to close " + Common.programName + "?",
				"Confirm Exit",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE);

		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}

		try
		{
			controller.stop();
		}
		catch (Exception ignored)
		{
			// shutting down anyway
		}
		dispose();
		System.exit(0);
	}

	private void setConfigEnabled(boolean enabled)
	{
		bindField.setEnabled(enabled);
		portSpinner.setEnabled(enabled);
		unitSpinner.setEnabled(enabled);
	}

	private static String describe(Throwable ex)
	{
		Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
		String message = cause.getMessage();
		return (message != null && !message.isBlank()) ? message : cause.toString();
	}

	/** Appends a timestamped line to the activity log. Safe to call from any thread. */
	public void log(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			logArea.append(timeFormat.format(new Date()) + "  " + message + "\n");
			int extra = logArea.getLineCount() - MAX_LOG_LINES;
			if (extra > 0)
			{
				try
				{
					logArea.replaceRange("", 0, logArea.getLineEndOffset(extra - 1));
				}
				catch (BadLocationException ignored)
				{
					// trimming is best-effort
				}
			}
			logArea.setCaretPosition(logArea.getDocument().getLength());
		});
	}

	/** Logs every change to the process image, whether made by a client or the operator. */
	private final class LoggingListener extends ModificationAdapter
	{
		@Override
		public void onCoilsModified(List<CoilModification> modifications)
		{
			for (CoilModification m : modifications)
			{
				log("Coil[" + m.address() + "] = " + m.value());
			}
		}

		@Override
		public void onDiscreteInputsModified(List<DiscreteInputModification> modifications)
		{
			for (DiscreteInputModification m : modifications)
			{
				log("Discrete Input[" + m.address() + "] = " + m.value());
			}
		}

		@Override
		public void onHoldingRegistersModified(List<HoldingRegisterModification> modifications)
		{
			for (HoldingRegisterModification m : modifications)
			{
				log("Holding Register[" + m.address() + "] = " + registerText(m.value()));
			}
		}

		@Override
		public void onInputRegistersModified(List<InputRegisterModification> modifications)
		{
			for (InputRegisterModification m : modifications)
			{
				log("Input Register[" + m.address() + "] = " + registerText(m.value()));
			}
		}

		private String registerText(byte[] value)
		{
			int v = (value != null && value.length >= 2) ? (((value[0] & 0xFF) << 8) | (value[1] & 0xFF)) : 0;
			return v + String.format(" (0x%04X)", v);
		}
	}
}
