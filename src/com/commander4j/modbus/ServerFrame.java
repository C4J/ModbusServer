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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
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

	private final JTextField bindField = new JTextField("0.0.0.0", 10);
	private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(502, 1, 65535, 1));
	private final JSpinner unitSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 247, 1));
	private final JToggleButton4j serverToggle = new JToggleButton4j(Common.icon_disconnected);
	private final JLabel4j_std statusLabel = new JLabel4j_std("Stopped");

	private final JTextArea logArea = new JTextArea(8, 80);
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

	private ServerConfig baseline = new ServerConfig("0.0.0.0", 502, 1);
	private File currentConfigFile = ConfigStore.DEFAULT_FILE;
	private boolean loading = false;
	private boolean dirty = false;

	public ServerFrame()
	{
		super(Common.buildTitle(null));
		JUtility.setLookAndFeel("Nimbus");
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

		serverToggle.setToolTipText("Start Server");
		serverToggle.setPreferredSize(new Dimension(36, 36));
		serverToggle.setMaximumSize(new Dimension(36, 36));
		serverToggle.addActionListener(_ -> toggleServer());

		add(buildConnectionBar(), BorderLayout.NORTH);

		UnifiedRegisterPanel registerPanel = new UnifiedRegisterPanel(controller);

		logArea.setEditable(false);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JScrollPane logScroll = new JScrollPane(logArea);
		logScroll.setBorder(BorderFactory.createTitledBorder("Activity"));
		logScroll.setPreferredSize(new Dimension(0, 200));

		JPanel registerArea = new JPanel(new BorderLayout());
		registerArea.add(registerPanel, BorderLayout.CENTER);
		registerArea.add(buildRightToolBar(registerPanel), BorderLayout.EAST);

		JPanel logRow = new JPanel(new BorderLayout());
		logRow.add(logScroll, BorderLayout.CENTER);
		logRow.add(buildLogToolBar(), BorderLayout.EAST);

		JPanel main = new JPanel(new BorderLayout());
		main.add(registerArea, BorderLayout.CENTER);
		main.add(logRow, BorderLayout.SOUTH);
		add(main, BorderLayout.CENTER);

		controller.getProcessImage().addModificationListener(new LoggingListener());

		installDirtyListeners();

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

		loadConfigOnStartup();
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
		bar.add(Box.createHorizontalStrut(12));
		bar.add(new JLabel4j_std("Status:"));
		bar.add(statusLabel);
		return bar;
	}

	private JToolBar buildRightToolBar(UnifiedRegisterPanel registerPanel)
	{
		JToolBar tb = new JToolBar(JToolBar.VERTICAL);
		tb.setFloatable(false);
		tb.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

		tb.add(serverToggle);

		tb.add(iconButton(Common.icon_open, "Open settings", _ -> openConfig()));
		tb.add(iconButton(Common.icon_save, "Save settings", _ -> saveConfig()));

		JPopupMenu zeroMenu = registerPanel.getZeroMenu();
		JButton4j zeroButton = new JButton4j(Common.icon_erase);
		zeroButton.setToolTipText("Zero registers");
		zeroButton.setPreferredSize(new Dimension(36, 36));
		zeroButton.setMaximumSize(new Dimension(36, 36));
		zeroButton.addActionListener(_ ->
				zeroMenu.show(zeroButton, -zeroMenu.getPreferredSize().width, 0));
		tb.add(zeroButton);

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

	private JToolBar buildLogToolBar()
	{
		JToolBar tb = new JToolBar(JToolBar.VERTICAL);
		tb.setFloatable(false);
		tb.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

		tb.add(iconButton(Common.icon_save,   "Save log",  _ -> saveLog()));
		tb.add(iconButton(Common.icon_eraser, "Clear log", _ -> clearLog()));

		return tb;
	}

	private void saveLog()
	{
		String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save activity log");
		chooser.setSelectedFile(new File("modbus-server-log-" + stamp + ".txt"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		File file = chooser.getSelectedFile();
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write(logArea.getText());
			log("Log saved to " + file.getAbsolutePath());
		}
		catch (IOException ex)
		{
			JOptionPane.showMessageDialog(this,
					"Failed to save log:\n\n" + describe(ex),
					"Save activity log",
					JOptionPane.ERROR_MESSAGE);
		}
	}

	private void clearLog()
	{
		logArea.setText("");
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
		if (dirty)
		{
			int saveChoice = JOptionPane.showConfirmDialog(
					ServerFrame.this,
					"Settings have changed. Save changes to\n" + currentConfigFile.getAbsolutePath() + "?",
					"Unsaved changes",
					JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.QUESTION_MESSAGE);

			if (saveChoice == JOptionPane.CANCEL_OPTION || saveChoice == JOptionPane.CLOSED_OPTION)
			{
				return;
			}
			if (saveChoice == JOptionPane.YES_OPTION && !writeConfig(currentConfigFile))
			{
				return;
			}
		}
		else
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

	private ServerConfig currentValues()
	{
		return new ServerConfig(
				bindField.getText().trim(),
				(Integer) portSpinner.getValue(),
				(Integer) unitSpinner.getValue());
	}

	private void installDirtyListeners()
	{
		bindField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent e)  { onUiChange(); }
			@Override public void removeUpdate(DocumentEvent e)  { onUiChange(); }
			@Override public void changedUpdate(DocumentEvent e) { onUiChange(); }
		});
		portSpinner.addChangeListener(_ -> onUiChange());
		unitSpinner.addChangeListener(_ -> onUiChange());
	}

	private void onUiChange()
	{
		if (loading)
		{
			return;
		}
		dirty = !currentValues().equals(baseline);
	}

	private void applyConfig(ServerConfig cfg)
	{
		loading = true;
		try
		{
			bindField.setText(cfg.bindAddress());
			portSpinner.setValue(cfg.port());
			unitSpinner.setValue(cfg.unitId());
		}
		finally
		{
			loading = false;
		}
		baseline = new ServerConfig(cfg.bindAddress().trim(), cfg.port(), cfg.unitId());
		dirty = false;
	}

	private void loadConfigOnStartup()
	{
		File file = ConfigStore.DEFAULT_FILE;
		if (!file.isFile())
		{
			log("No saved settings at " + file.getAbsolutePath() + " - using defaults.");
			return;
		}
		try
		{
			ServerConfig cfg = ConfigStore.load(file);
			applyConfig(cfg);
			currentConfigFile = file;
			log("Loaded settings from " + file.getAbsolutePath());
		}
		catch (Exception ex)
		{
			log("Failed to load settings from " + file.getAbsolutePath() + ": " + describe(ex));
		}
	}

	private void openConfig()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Open settings");
		chooser.setFileFilter(new FileNameExtensionFilter("XML files (*.xml)", "xml"));
		File dir = ConfigStore.DEFAULT_FILE.getParentFile();
		if (dir != null && dir.isDirectory())
		{
			chooser.setCurrentDirectory(dir);
		}
		File preset = currentConfigFile.isFile() ? currentConfigFile
				: new File(dir != null ? dir : new File("."), ConfigStore.DEFAULT_FILE.getName());
		chooser.setSelectedFile(preset);
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		File file = chooser.getSelectedFile();
		try
		{
			ServerConfig cfg = ConfigStore.load(file);
			applyConfig(cfg);
			currentConfigFile = file;
			log("Loaded settings from " + file.getAbsolutePath());
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this,
					"Failed to load settings:\n\n" + describe(ex),
					"Open settings",
					JOptionPane.ERROR_MESSAGE);
		}
	}

	private void saveConfig()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save settings");
		chooser.setFileFilter(new FileNameExtensionFilter("XML files (*.xml)", "xml"));
		File dir = ConfigStore.DEFAULT_FILE.getParentFile();
		if (dir != null)
		{
			if (!dir.isDirectory())
			{
				dir.mkdirs();
			}
			if (dir.isDirectory())
			{
				chooser.setCurrentDirectory(dir);
			}
		}
		File preset = currentConfigFile != null ? currentConfigFile
				: new File(dir != null ? dir : new File("."), ConfigStore.DEFAULT_FILE.getName());
		chooser.setSelectedFile(preset);
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		File file = chooser.getSelectedFile();
		if (!file.getName().contains("."))
		{
			file = new File(file.getParentFile(), file.getName() + ".xml");
		}
		writeConfig(file);
	}

	private boolean writeConfig(File file)
	{
		ServerConfig cfg = currentValues();
		try
		{
			ConfigStore.save(file, cfg);
			baseline = cfg;
			dirty = false;
			currentConfigFile = file;
			log("Saved settings to " + file.getAbsolutePath());
			return true;
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this,
					"Failed to save settings:\n\n" + describe(ex),
					"Save settings",
					JOptionPane.ERROR_MESSAGE);
			return false;
		}
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
