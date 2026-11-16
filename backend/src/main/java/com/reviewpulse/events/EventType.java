package com.reviewpulse.events;

/**
 * Review change events. The type is informational: consumers are state-based and apply the full
 * review snapshot carried by the event if its version is newer, whatever the type says.
 */
public enum EventType {
    REVIEW_CREATED,
    REVIEW_UPDATED,
    REVIEW_DELETED,
    HELPFULNESS_UPDATED
}
