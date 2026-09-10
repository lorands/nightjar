package dev.nightjar.domainevent.spi

/** Generates unique event ids. Default implementation: UUIDv7 (time-sortable). */
public fun interface IdGenerator {

    public fun nextId(): String
}
