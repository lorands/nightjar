plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
}

description = "Schema-migration manifest convention and the data-migration engine for reprocessing entities in batches."

dependencies {
    // Zero external dependencies — domain-event is nightjar's own (also zero-dep);
    // reused for the TransactionalRunner SPI and event-driven enqueue composition.
    api(project(":domain-event"))
}
