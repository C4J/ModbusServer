package com.commander4j.modbus;

/**
 * Immutable snapshot of the three Modbus server settings persisted to {@code xml/config/config.xml}:
 * bind address (IP), TCP port, and Modbus unit ID. Used by {@link ConfigStore} for load/save and by
 * {@link ServerFrame} as the baseline against which UI edits are compared to determine the
 * "unsaved changes" state.
 */
public record ServerConfig(String bindAddress, int port, int unitId)
{
}
