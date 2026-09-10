package dev.nightjar.examples;

import dev.nightjar.domainevent.DomainEvent;
import dev.nightjar.domainevent.DomainEventBus;
import dev.nightjar.domainevent.inmemory.InMemoryOutboxStore;
import dev.nightjar.domainevent.inmemory.InProcessEventTransport;
import dev.nightjar.domainevent.spi.EventSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The same library from plain Java — records as events, lambdas as listeners,
 * a fluent builder. No Kotlin knowledge required to consume nightjar.
 */
public final class JavaQuickstart {

    /** Events can be Java records. {@code sequenceKey} serializes per-order processing. */
    public record OrderShipped(String orderId, String trackingCode) implements DomainEvent {

        @Override
        public String sequenceKey() {
            return orderId();
        }
    }

    /** Hand-rolled serializer — adapt Jackson in a real application. */
    public static final EventSerializer SERIALIZER = new EventSerializer() {

        @Override
        public byte[] serialize(DomainEvent event) {
            OrderShipped shipped = (OrderShipped) event;
            return (shipped.orderId() + "|" + shipped.trackingCode()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public DomainEvent deserialize(String type, byte[] payload) {
            if (!"OrderShipped".equals(type)) {
                throw new IllegalArgumentException("Unknown event type: " + type);
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|");
            return new OrderShipped(parts[0], parts[1]);
        }
    };

    public static void main(String[] args) throws Exception {
        DomainEventBus bus = DomainEventBus.builder()
                .serializer(SERIALIZER)
                .store(new InMemoryOutboxStore())
                .transport(new InProcessEventTransport())
                .pollInterval(Duration.ofMillis(20))
                .metadataProvider(() -> java.util.Map.of("origin", "java-quickstart"))
                .build();

        bus.subscribeSync(OrderShipped.class, envelope ->
                System.out.println("[sync ] order " + envelope.getEvent().orderId()));

        bus.subscribe(OrderShipped.class, envelope ->
                System.out.println("[async] tracking " + envelope.getEvent().trackingCode()
                        + " metadata=" + envelope.getMetadata()));

        bus.start();
        try (bus) {
            bus.publish(new OrderShipped("order-7", "TRACK-123"));
            Thread.sleep(300); // demo only
        }
    }

    private JavaQuickstart() {
    }
}
