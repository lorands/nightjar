
The following are our hard line requirements:
- minimum dependendencies: no framework dependencies, avoid runtime depdendencies where ever possible
- maximum compatibility: abstract and give one optional solution (for example RabbitMQ, but leave room for others like NATS, Kafka, etc.)
- small and lean: keep the codebase minimal and focused
- must be DDD and Cloud-Native
- always look out for existing solutions and avoid reinventing the wheel, - but only except existing solutions that align with our requirements and are open source, well-maintained, and actively developed
- always make examples for Java and Kotlin, also create wiring examples for Spring Boot and Quarkus
- documentation: provide clear and concise documentation for each feature, including usage examples and configuration options. The bare minimum is to have a README.md file with installation instructions, usage examples, and a list of dependencies, source code documentation, and a user manual under docs in AsciiDoc format.
- must have high-quality tests for all features
- it has to be GraalVM compatible