package com.govorun.echo.smart

import org.json.JSONObject
import java.io.IOException

/**
 * The socket the smart functions take their answers from: a task in — its [kind] («card», «ask», «brief_part», «brief») and its
 * input — and the answer's JSON out. Plugged in: the Govorun app on the phone, which sends the task to Govorun's server with its
 * account's blind passes. The prompts live on that server only; the phone sends the task and its data, never a prompt. Called only on
 * a background thread.
 */
fun interface Writer {
    /** The answer, a JSON object as text; throws [IOException] when there is none (no network, refused, limit, off). */
    @Throws(IOException::class)
    fun write(
        kind: String,
        input: JSONObject,
    ): String
}
