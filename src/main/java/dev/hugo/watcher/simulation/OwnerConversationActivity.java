package dev.hugo.watcher.simulation;

/** Whether the owner has sent a verified outgoing message in this canonical conversation recently. */
public enum OwnerConversationActivity {
    ACTIVE,
    INACTIVE,
    UNKNOWN
}
