package dev.nightjar.spring

import com.rabbitmq.client.ConnectionFactory
import dev.nightjar.domainevent.rabbitmq.RabbitMqEventTransport
import dev.nightjar.domainevent.spi.EventTransport
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * RabbitMQ transport — active when `nightjar.domain-event.transport=rabbitmq`
 * and `dev.nightjar:domain-event-rabbitmq` is on the classpath. Registered
 * before [NightjarDomainEventAutoConfiguration], whose in-process transport
 * backs off to any existing [EventTransport] bean.
 */
@AutoConfiguration(before = [NightjarDomainEventAutoConfiguration::class])
@ConditionalOnClass(RabbitMqEventTransport::class)
@ConditionalOnProperty(prefix = "nightjar.domain-event", name = ["transport"], havingValue = "rabbitmq")
@EnableConfigurationProperties(NightjarProperties::class)
public class NightjarRabbitMqTransportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public fun nightjarRabbitMqEventTransport(properties: NightjarProperties): EventTransport {
        val rabbit = properties.domainEvent.rabbitmq
        val connectionFactory = ConnectionFactory().apply {
            host = rabbit.host
            port = rabbit.port
            username = rabbit.username
            password = rabbit.password
        }
        return RabbitMqEventTransport(connectionFactory, rabbit.queue, rabbit.prefetch)
    }
}
