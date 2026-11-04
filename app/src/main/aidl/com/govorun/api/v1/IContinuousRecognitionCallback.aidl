package com.govorun.api.v1;

oneway interface IContinuousRecognitionCallback {
    void onReady(String sessionId);
    void onSpeechStateChanged(String sessionId, boolean active);
    void onFinalResult(String sessionId, long sequence, String text);
    void onStopped(String sessionId);
    void onError(String sessionId, int code, String safeMessage);

    /**
     * Same segment as onFinalResult, with word times in milliseconds from the start of the
     * stream. Delivered in addition to onFinalResult, only to clients that opted in with
     * CONTINUOUS_FLAG_WORD_TIMESTAMPS (capability CAPABILITY_WORD_TIMESTAMPS).
     */
    void onFinalResultTimed(
        String sessionId,
        long sequence,
        String text,
        in long[] wordStartsMs,
        in long[] wordEndsMs,
        in String[] words
    );

    /**
     * Govorun's own dictation (the bird) started (active = true) or ended (false) while this
     * stream was open. Only the fact and the time: elapsedRealtimeMs is SystemClock.elapsedRealtime()
     * at the change. While it lasts Govorun skips this stream's audio, so the dictation is never
     * recognized into it; the stream stays open. Delivered only to clients that opted in with
     * CONTINUOUS_FLAG_DICTATION_SIGNAL (capability CAPABILITY_DICTATION_SIGNAL); other clients are
     * preempted with ERROR_PREEMPTED as before.
     */
    void onPrimaryDictation(String sessionId, boolean active, long elapsedRealtimeMs);
}
