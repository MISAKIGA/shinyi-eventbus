package com.shinyi.eventbus.monitor;

/**
 * Reset strategy for metrics
 */
public enum ResetStrategy {
    /**
     * Never reset - cumulative metrics only
     */
    NEVER,

    /**
     * Reset at midnight every day
     */
    DAILY,

    /**
     * Reset at the top of every hour
     */
    HOURLY,

    /**
     * Reset at a fixed interval (configurable via resetIntervalSeconds)
     */
    INTERVAL,

    /**
     * Only reset when explicitly called (manual trigger)
     */
    MANUAL
}
