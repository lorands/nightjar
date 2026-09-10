package dev.nightjar.spring

import dev.nightjar.domainevent.DomainEvent
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class TestApp

/** Jackson-friendly without jackson-module-kotlin: default ctor + mutable property. */
class OrderPlaced @JvmOverloads constructor(var orderId: String = "") : DomainEvent
