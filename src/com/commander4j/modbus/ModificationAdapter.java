package com.commander4j.modbus;

import java.util.List;

import com.digitalpetri.modbus.server.ProcessImage;
import com.digitalpetri.modbus.server.ProcessImage.Modification.CoilModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.DiscreteInputModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.HoldingRegisterModification;
import com.digitalpetri.modbus.server.ProcessImage.Modification.InputRegisterModification;

/**
 * Empty {@link ProcessImage.ModificationListener} implementation. Subclasses override only
 * the callback(s) for the table(s) they care about.
 *
 * <p>Every callback is invoked on the thread that performed the write - a Netty I/O thread
 * for client writes, or the Swing event dispatch thread for operator edits - while the
 * relevant {@code ProcessImage} lock is held. Implementations must therefore be quick and
 * non-blocking, and must not start a new {@code ProcessImage} transaction.
 */
public class ModificationAdapter implements ProcessImage.ModificationListener
{

	@Override
	public void onCoilsModified(List<CoilModification> modifications)
	{
	}

	@Override
	public void onDiscreteInputsModified(List<DiscreteInputModification> modifications)
	{
	}

	@Override
	public void onHoldingRegistersModified(List<HoldingRegisterModification> modifications)
	{
	}

	@Override
	public void onInputRegistersModified(List<InputRegisterModification> modifications)
	{
	}
}
