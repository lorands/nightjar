plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
    id("nightjar.integration-test")
}

description = "RabbitMQ transport for nightjar domain events, with poison-message dead-lettering."

dependencies {
    api(project(":domain-event"))
    // The single runtime dependency of this adapter — the official low-level Java client
    api(libs.amqp.client)

    testImplementation(libs.rabbitmq.mock)
}
