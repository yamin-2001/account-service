package com.banking.account.messaging;

public final class TopicNames {

    private TopicNames() {}

    public static final String ACCOUNT_COMMANDS = "account.commands";
    public static final String ACCOUNT_EVENTS = "account.events";
    public static final String CBS_COMMANDS = "cbs.commands";
    public static final String CBS_EVENTS = "cbs.events";

    /** A message that failed all its retries is parked on "<topic>.DLT". */
    public static final String DLT_SUFFIX = ".DLT";
}