package com.banking.account.messaging.consumer;

import com.banking.account.messaging.AccountCommandService;
import com.banking.account.messaging.EventEnvelope;
import com.banking.account.messaging.EventParser;
import com.banking.account.messaging.TopicNames;
import com.banking.account.messaging.payload.ValidateCashAccountCommand;
import com.banking.account.messaging.payload.ValidateTransferCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AccountCommandsConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountCommandsConsumer.class);

    private final EventParser parser;
    private final AccountCommandService commands;

    public AccountCommandsConsumer(EventParser parser, AccountCommandService commands) {
        this.parser = parser;
        this.commands = commands;
    }

    @KafkaListener(topics = TopicNames.ACCOUNT_COMMANDS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        switch (envelope.eventType()) {
            case "ValidateTransfer" -> commands.validateTransfer(envelope,
                    parser.payload(envelope, ValidateTransferCommand.class));
            case "ValidateCashAccount" -> commands.validateCashAccount(envelope,
                    parser.payload(envelope, ValidateCashAccountCommand.class));
            default -> log.debug("Ignoring unknown command type {}", envelope.eventType());
        }
    }
}