plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
}

description = "Cluster-coordination primitives for multi-instance JVM applications: a leased process lock, a worker pool and a persistent scheduler. No runtime dependencies."

dependencies {
    // Zero external dependencies — domain-event is nightjar's own (also zero-dep);
    // reused for the TransactionalRunner SPI (scheduler jobs run in transactions).
    api(project(":domain-event"))
}
