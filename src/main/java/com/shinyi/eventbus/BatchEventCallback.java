package com.shinyi.eventbus;

import java.util.List;

/**
 * Batch event callback interface for batch publish operations.
 * Provides notifications when batch publish completes or fails.
 */
public interface BatchEventCallback {

    /**
     * Called when batch publish completes successfully.
     *
     * @param results list of EventResult, one per event in the batch
     */
    void onBatchComplete(List<EventResult> results);

    /**
     * Called when batch publish fails.
     *
     * @param results list of EventResult (partial results may be available)
     * @param exception the error that caused the failure
     */
    void onBatchFailure(List<EventResult> results, Throwable exception);
}
