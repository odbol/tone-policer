package com.odbol.tonepolicer.inference

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tone analysis with Jev, TypeSafe AI's decision model ("System One").
 *
 * Jev generates no text. You give it a `state` plus typed questions, and it returns typed answers:
 * - **Score**: a position on an ordered rubric. Used here for the overall severity.
 * - **Choice**: one key from a set you define. Used here for the main problem category.
 * - **Noul**: the probability (0..1) that a statement is true. Used here for each social cue
 *   (did the reply ignore their point, skip a thank-you, ...).
 *
 * Everything is asked in one request, and the answers map directly onto [ToneResult] with no text
 * parsing. Jev can't write rewrites, so it implements only [ToneAnalyzer]; suggestions come from a
 * Gemini [RewriteSuggester].
 */
class JevAnalyzer(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ToneAnalyzer {
    override val name = "Jev ($model)"

    /** A yes/no social-expectation check. "Yes" means the reply has the problem. */
    private class Cue(val key: String, val instructions: String, val tip: String, val category: String)

    private val cues = listOf(
        Cue(
            "ignores_point",
            "The other person made a point, raised a concern or asked something, and the user's message doesn't acknowledge it before replying, disagreeing or changing the subject.",
            "Acknowledge their point before replying", "dismissive",
        ),
        Cue(
            "lacks_empathy",
            "The other person shared a feeling, a problem, stress or bad news, and the user's message shows no empathy for it.",
            "Show empathy for what they're going through", "unempathetic",
        ),
        Cue(
            "misses_congrats",
            "The other person shared good news or an achievement, and the user's message doesn't congratulate or celebrate it.",
            "Congratulate them on their news", "missing_niceties",
        ),
        Cue(
            "misses_thanks",
            "The other person helped, did a favor, sent something, gave information or paid a compliment, and the user's message doesn't thank them.",
            "Thank them", "missing_niceties",
        ),
        Cue(
            "misses_apology",
            "The user is at fault, replying late, or confused, and the message doesn't apologize (e.g. \"Sorry for the slow reply\", \"Sorry, I didn't quite understand that\").",
            "Apologize briefly, e.g. \"Sorry, I didn't quite understand that\"", "missing_niceties",
        ),
        Cue(
            "bossy_request",
            "The user's message asks for something or gives a command without \"please\", \"could you\" or any other softening.",
            "Soften the request with \"could you\" or \"please\"", "missing_niceties",
        ),
        Cue(
            "unanswered_question",
            "The other person asked a question, and the user's message doesn't answer it.",
            "Answer the question they asked", "dismissive",
        ),
        Cue(
            "effort_mismatch",
            "The user's message is much shorter, colder or less engaged than the other person's message, e.g. a one-word reply to a long or heartfelt message.",
            "Match their effort with a fuller reply", "standoffish",
        ),
        Cue(
            "bare_refusal",
            "The user's message says no or declines without giving a reason or offering an alternative.",
            "Give a reason or offer an alternative", "standoffish",
        ),
        Cue(
            "self_centered",
            "The other person shared something personal, and the user's message turns the conversation to the user instead of responding to it.",
            "Respond to what they shared before talking about yourself", "socially_awkward",
        ),
        Cue(
            "sarcasm",
            "The user's message uses sarcasm or a joke that could easily read as hostile or mocking in text.",
            "Drop the sarcasm; it can read as hostile in text", "inflammatory",
        ),
    )

    /** Choice options for the main problem: key -> description sent to Jev, and the reason shown to the user. */
    private val issues = linkedMapOf(
        "none" to ("The message is fine: friendly or neutral and fits the conversation." to ""),
        "insulting" to ("Insults, name-calling or put-downs." to "Name-calling and put-downs make the reader defensive."),
        "inappropriate" to ("Profanity, crude or offensive content." to "Profanity or crude wording can read as aggressive."),
        "inflammatory" to ("Accusations, blame, absolutes like \"you always\", or shouting." to "Blame and absolutes tend to escalate conflict."),
        "passive_aggressive" to ("Passive-aggressive phrasing such as \"per my last email\" or pointed politeness." to "This phrasing tends to sound passive-aggressive."),
        "dismissive" to ("Brushes off or ignores what the other person said." to "It brushes off what the other person said."),
        "standoffish" to ("Cold, curt or distant." to "It reads as cold or curt."),
        "unprofessional" to ("Too casual or sloppy for the setting." to "It's more casual than the setting calls for."),
        "unempathetic" to ("Ignores the other person's feelings, bad news or stress." to "It doesn't acknowledge how they're feeling."),
        "missing_niceties" to ("Missing an expected please, thank-you, apology or greeting." to "It skips an expected please, thank-you or apology."),
        "socially_awkward" to ("Awkward or off-topic for the conversation." to "It doesn't quite fit the conversation."),
    )

    /** Score levels, low to high, as concrete situations (Jev's docs recommend that over bare labels). */
    private val severityLevels = listOf(
        "Friendly, warm or neutral, and it fits the conversation.",
        "Slightly curt, blunt or awkward, or missing an expected nicety such as a thank-you or acknowledgement.",
        "Likely to irritate or hurt: dismissive, passive-aggressive, or ignores the other person's feelings.",
        "Rude or offensive: insulting, profane or inflammatory.",
        "Hostile, abusive or highly inappropriate.",
    )

    override suspend fun status(): EngineStatus =
        if (apiKey.isBlank()) EngineStatus.NeedsApiKey("Jev") else EngineStatus.Ready

    override suspend fun analyze(request: ToneRequest): ToneResult {
        if (apiKey.isBlank()) throw InferenceException("No Jev API key set")
        val body = JSONObject()
            .put("model", model)
            .put("state", state(request))
            .put("questions", questions())
        val response = HttpJson.post(
            url = baseUrl,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body,
            service = "Jev API",
        )
        return toResult(response.optJSONObject("answers") ?: response)
    }

    private fun state(request: ToneRequest): String = listOfNotNull(
        if (request.packageName.isBlank()) null else "App: ${request.packageName}",
        if (request.screenContext.isBlank()) {
            null
        } else {
            "Conversation visible on screen, top to bottom. Lines starting with [left] are usually from the " +
                "other person, [right] are the user's own earlier messages, and other lines may be names, " +
                "timestamps or app labels:\n" + request.screenContext.takeLast(Prompts.MAX_CONTEXT_CHARS)
        },
        "Message the user is about to send:\n" + request.text.takeLast(Prompts.MAX_MESSAGE_CHARS),
    ).joinToString("\n\n")

    private fun questions() = JSONObject().apply {
        put(
            Q_SEVERITY,
            JSONObject()
                .put("type", "score")
                .put("instructions", "How badly is the user's message likely to land with the person reading it, given the conversation?")
                .put("criteria", JSONArray(severityLevels)),
        )
        put(
            Q_ISSUE,
            JSONObject()
                .put("type", "choice")
                .put("instructions", "What is the main problem with how the user's message comes across?")
                .put("criteria", JSONObject().apply { issues.forEach { (key, value) -> put(key, value.first) } }),
        )
        for (cue in cues) {
            put(
                cue.key,
                JSONObject()
                    .put("type", "noul")
                    .put(
                        "instructions",
                        "${cue.instructions} Answer yes only if the conversation clearly calls for it and the message misses it.",
                    ),
            )
        }
    }

    private fun toResult(answers: JSONObject): ToneResult {
        val severityAnswer = answers.optJSONObject(Q_SEVERITY)
            ?: throw InferenceException("Jev returned no severity answer")
        val position = scorePosition(severityAnswer)
        var severity = (position / (severityLevels.size - 1) * 100).toInt().coerceIn(0, 100)

        val missedCues = cues
            .map { it to noulProbability(answers.optJSONObject(it.key)) }
            .filter { (_, p) -> p >= CUE_THRESHOLD }
            .sortedByDescending { (_, p) -> p }
            .map { (cue, _) -> cue }
        // A missed social cue alone is a "16-40" problem, even if the wording itself is fine.
        if (missedCues.isNotEmpty()) severity = maxOf(severity, minOf(40, 16 + 8 * missedCues.size))

        val issue = answers.optJSONObject(Q_ISSUE)?.let(::choiceKey)?.takeIf { it in issues && it != "none" }
        val categories = (listOfNotNull(issue) + missedCues.map { it.category }).distinct()
        val reason = issue?.let { issues.getValue(it).second }
            ?: if (missedCues.isNotEmpty()) "The reply misses a social cue from the conversation." else ""

        return ToneResult(
            severity = severity,
            categories = categories,
            reason = reason,
            engine = name,
            missing = missedCues.take(3).map { it.tip },
        )
    }

    /** The Score answer's position along the levels (0 = first level); may fall between two levels. */
    private fun scorePosition(answer: JSONObject): Double {
        val score = answer.optDouble("score", Double.NaN)
        if (!score.isNaN()) return score.coerceIn(0.0, (severityLevels.size - 1).toDouble())
        // Fall back to the expected value of the per-level probabilities.
        val probabilities = probabilities(answer)
        if (probabilities.isEmpty()) throw InferenceException("Jev severity answer has no score")
        return probabilities.entries.sumOf { (level, p) -> (level.toIntOrNull() ?: 0) * p } /
            probabilities.values.sum().coerceAtLeast(1e-9)
    }

    /** The selected key of a Choice answer, or the most probable option if no key is given. */
    private fun choiceKey(answer: JSONObject): String? {
        for (field in listOf("choice", "selected", "answer", "value")) {
            answer.optString(field).takeIf { it.isNotBlank() }?.let { return it }
        }
        return probabilities(answer).maxByOrNull { it.value }?.key
    }

    private fun noulProbability(answer: JSONObject?): Double {
        if (answer == null) return 0.0
        for (field in listOf("noul", "probability", "value")) {
            val p = answer.optDouble(field, Double.NaN)
            if (!p.isNaN()) return p
        }
        return if (answer.optBoolean("answer")) 1.0 else 0.0
    }

    /** Reads `probabilities` given either as an object keyed by option or as an array by level. */
    private fun probabilities(answer: JSONObject): Map<String, Double> {
        answer.optJSONObject("probabilities")?.let { obj ->
            return obj.keys().asSequence().associateWith { obj.optDouble(it, 0.0) }
        }
        answer.optJSONArray("probabilities")?.let { arr ->
            return (0 until arr.length()).associate { it.toString() to arr.optDouble(it, 0.0) }
        }
        return emptyMap()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.typesafe.ai/v1/systemone"
        const val DEFAULT_MODEL = "jev-latest"

        private const val Q_SEVERITY = "severity"
        private const val Q_ISSUE = "main_issue"
        private const val CUE_THRESHOLD = 0.6
    }
}
