package com.vwsdigital.blocklog.db;

/** A row read back from the database. */
public record LoggedChange(long id, long time, String world, int x, int y, int z, String oldState, String newState, String playerUuid, String playerName) {}
