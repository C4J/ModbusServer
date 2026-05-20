package com.commander4j.modbus;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FlowLayout;
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
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

import com.digitalpetri.modbus.server.ProcessImage.Modification.CoilModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.DiscreteInputModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.HoldingRegisterModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.InputRegisterModification;

/**
 * Main application window: a connection bar, one tab per Modbus data table, and an activity
 * log. The window owns a single {@link ServerController}; starting and stopping the server
 * is done on a background thread so the bind/unbind call never blocks the event dispatch
 * thread.
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
	private final JButton startButton = new JButton("Start");
	private final JButton stopButton = new JButton("Stop");
	private final JLabel statusLabel = new JLabel("Stopped");

	private final JTextArea logArea = new JTextArea(8, 80);
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

	public ServerFrame()
	{
		super("Commander4j Modbus Server");
		setDefaultCloseOperation(EXIT_ON_CLOSE);

		add(buildConnectionBar(), BorderLayout.NORTH);

		JTabbedPane tabs = new JTabbedPane();
		for (RegisterKind kind : RegisterKind.values())
		{
			tabs.addTab(kind.label, new RegisterTablePanel(controller, kind));
		}

		logArea.setEditable(false);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JScrollPane logScroll = new JScrollPane(logArea);
		logScroll.setBorder(BorderFactory.createTitledBorder("Activity"));

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logScroll);
		split.setResizeWeight(0.75);
		add(split, BorderLayout.CENTER);

		startButton.addActionListener(_ -> doStart());
		stopButton.addActionListener(_ -> doStop());
		stopButton.setEnabled(false);

		controller.getProcessImage().addModificationListener(new LoggingListener());

		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				try
				{
					controller.stop();
				}
				catch (Exception ignored)
				{
					// shutting down anyway
				}
			}
		});

		setSize(840, 660);
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
		bar.add(new JLabel("Bind address:"));
		bar.add(bindField);
		bar.add(new JLabel("Port:"));
		bar.add(portSpinner);
		bar.add(new JLabel("Unit ID:"));
		bar.add(unitSpinner);
		bar.add(startButton);
		bar.add(stopButton);
		bar.add(Box.createHorizontalStrut(12));
		bar.add(new JLabel("Status:"));
		bar.add(statusLabel);
		return bar;
	}

	private void doStart()
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
		startButton.setEnabled(false);
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
					stopButton.setEnabled(true);
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
					startButton.setEnabled(true);
					JOptionPane.showMessageDialog(ServerFrame.this, "Could not start the Modbus server:\n\n" + reason, "Modbus Server", JOptionPane.ERROR_MESSAGE);
				});
			}
		}, "modbus-server-start").start();
	}

	private void doStop()
	{
		stopButton.setEnabled(false);
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
				startButton.setEnabled(true);
				log("Server stopped.");
			});
		}, "modbus-server-stop").start();
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
