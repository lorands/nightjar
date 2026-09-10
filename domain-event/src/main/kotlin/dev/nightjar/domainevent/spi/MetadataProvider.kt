package dev.nightjar.domainevent.spi

/**
 * Supplies contextual metadata captured at publish time and carried in the
 * [dev.nightjar.domainevent.EventEnvelope] — current user, tenant, trace id...
 *
 * Generalizes a fixed "who did this" context object: put whatever your
 * listeners need under your own keys.
 */
public fun interface MetadataProvider {

    public fun current(): Map<String, String>

    public companion object {

        /** No metadata. */
        @JvmField
        public val EMPTY: MetadataProvider = MetadataProvider { emptyMap() }
    }
}
