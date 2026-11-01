package com.banking.account.messaging.consumer;

import com.banking.account.messaging.AccountOpeningService;
import com.banking.account.messaging.EventEnvelope;
import com.banking.account.messaging.EventParser;
import com.banking.account.messaging.TopicNames;
import com.banking.account.messaging.payload.LedgerAccountCreatedEvent;
import com.banking.account.messaging.payload.LedgerAccountCreationFailedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CbsEventsConsumer {

    private final EventParser parser;
    private final AccountOpeningService opening;

    public CbsEventsConsumer(EventParser parser, AccountOpeningService opening) {
        this.parser = parser;
        this.opening = opening;
    }

    @KafkaListener(topics = TopicNames.CBS_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        switch (envelope.eventType()) {
            case "LedgerAccountCreated" -> opening.ledgerAccountCreated(envelope,
                    parser.payload(envelope, LedgerAccountCreatedEvent.class));
            case "LedgerAccountCreationFailed" -> opening.ledgerAccountCreationFailed(envelope,
                    parser.payload(envelope, LedgerAccountCreationFailedEvent.class));
            default -> {
                // Withdraw, deposit and reversal results are for Fund Transfer.
            }
        }
    }
}