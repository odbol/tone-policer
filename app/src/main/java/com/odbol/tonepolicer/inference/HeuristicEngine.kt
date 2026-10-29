package com.odbol.tonepolicer.inference

/**
 * Keyword-based fallback for devices without Gemini Nano and without an API key (e.g. emulators).
 * Crude, but lets the overlay be exercised fully offline.
 */
class HeuristicEngine : InferenceEngine {
    override val name = "Offline heuristic"

    private class Rule(val category: String, val weight: Int, val reason: String, terms: List<String>) {
        val regexes = terms.map { Regex("""(?<![\p{L}'])${Regex.escape(it)}(?![\p{L}'])""", RegexOption.IGNORE_CASE) }
    }

    private val rules = listOf(
        Rule(
            "insulting", 45, "Name-calling makes the reader defensive.",
            listOf("idiot", "idiots", "stupid", "moron", "dumb", "incompetent", "useless", "pathetic", "loser", "clown", "jerk", "dumbass", "clueless"),
        ),
        Rule(
            "inappropriate", 35, "Profanity can read as aggressive.",
            listOf("fuck", "fucking", "fucked", "shit", "bullshit", "crap", "wtf", "asshole", "bitch", "damn", "pissed"),
        ),
        Rule(
            "inflammatory", 30, "Absolutes and accusations escalate conflict.",
            listOf("you always", "you never", "shut up", "how dare", "are you kidding", "ridiculous", "unacceptable", "what is wrong with you", "your fault"),
        ),
        Rule(
            "passive_aggressive", 25, "This phrasing tends to sound passive-aggressive.",
            listOf("per my last email", "as i already said", "as previously stated", "as i mentioned", "just to be clear", "friendly reminder", "not sure why", "thanks in advance", "with all due respect"),
        ),
        Rule(
            "standoffish", 22, "This sounds dismissive or cold.",
            listOf("whatever", "not my problem", "figure it out", "do it yourself", "i don't care", "who cares", "fine.", "k.", "no.", "deal with it"),
        ),
        Rule(
            "unprofessional", 12, "Very casual slang may not suit every audience.",
            listOf("lol", "lmao", "bro", "dude", "omg"),
        ),
    )

    private val softenings = linkedMapOf(
        "per my last email" to "as mentioned earlier",
        "as i already said" to "to recap",
        "friendly reminder" to "quick reminder",
        "you always" to "it sometimes seems like you",
        "you never" to "I don't think you've had a chance to",
        "whatever" to "okay",
        "not my problem" to "I'm not the best person to handle this",
        "figure it out" to "let me know if you get stuck",
        "shut up" to "let's pause for a second",
        "ridiculous" to "surprising",
        "unacceptable" to "not quite what we need",
        "stupid" to "confusing",
        "idiot" to "person",
        "useless" to "not helpful yet",
    )

    /** A situation in the conversation that calls for a particular response. */
    private class Cue(
        val category: String,
        val weight: Int,
        val tip: String,
        val opener: String,
        val trigger: Regex,
        val satisfiedBy: Regex,
    )

    private val cues = listOf(
        Cue(
            "unempathetic", 30, "Acknowledge how they're feeling", "I'm sorry to hear that, that sounds really tough.",
            Regex("""\b(ugh|sick|passed away|died|funeral|hospital|rough (day|week)|stressed|anxious|exhausted|frustrat\w*|broke up|lost my|bad news|struggling|overwhelmed|cancell?ed|stuck|terrible|awful|worst|nightmare|fired|laid off)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(sorry|that sucks|tough|hope you|here for you|must be|sounds (hard|rough|awful|frustrating|stressful)|hugs|take care)\b""", RegexOption.IGNORE_CASE),
        ),
        Cue(
            "missing_niceties", 20, "Congratulate them", "Congratulations, that's great news!",
            Regex("""\b(got the job|promoted|promotion|engaged|got married|pregnant|graduated|we won|got accepted|good news|finally did it)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(congrat\w*|amazing|awesome|great news|so happy|proud|well done)\b""", RegexOption.IGNORE_CASE),
        ),
        Cue(
            "missing_niceties", 15, "Thank them for what they did", "Thanks so much for this!",
            Regex("""\b(here you go|attached|i('ve| have)? sent|i fixed|i finished|i made|i did it|all done|i took care of)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(thank\w*|thx|ty|appreciate\w*|cheers)\b""", RegexOption.IGNORE_CASE),
        ),
        Cue(
            "socially_awkward", 12, "Say how you're doing and ask about them", "I'm doing well, thanks! How about you?",
            Regex("""\b(how are you|how's it going|how have you been|how was your)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(good|great|fine|well|not bad|okay|and you|how about you|you\?)\b""", RegexOption.IGNORE_CASE),
        ),
        Cue(
            "missing_niceties", 8, "Say \"you're welcome\"", "You're welcome!",
            Regex("""\b(thank you|thanks)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(welcome|no problem|no worries|anytime|happy to|glad|np)\b""", RegexOption.IGNORE_CASE),
        ),
    )

    private val bareCommand = Regex("""^\s*(send|give|do|fix|tell|call|get|make|stop|reply|answer|check|update)\b""", RegexOption.IGNORE_CASE)
    private val politeMarkers = Regex("""\b(please|pls|thanks|thank you|could you|would you|can you|when you get a chance)\b""", RegexOption.IGNORE_CASE)

    /** Cues triggered by the most recent on-screen text that the reply doesn't address. */
    private fun missedCues(request: ToneRequest): List<Cue> {
        // Approximate "their latest message" with the text just above the field.
        val recent = request.screenContext.takeLast(600)
        if (recent.isBlank()) return emptyList()
        return cues.filter { it.trigger.containsMatchIn(recent) && !it.satisfiedBy.containsMatchIn(request.text) }
    }

    override suspend fun status() = EngineStatus.Ready

    override suspend fun analyze(request: ToneRequest): ToneResult {
        val text = request.text
        var score = 0
        val categories = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var reason = ""
        for (rule in rules) {
            val hits = rule.regexes.count { it.containsMatchIn(text) }
            if (hits == 0) continue
            // Diminishing returns for repeated hits in the same category.
            score += rule.weight + (hits - 1) * rule.weight / 3
            categories += rule.category
            if (reason.isEmpty()) reason = rule.reason
        }
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 8 && letters.count { it.isUpperCase() } > letters.length * 0.6) {
            score += 25
            categories += "inflammatory"
            if (reason.isEmpty()) reason = "ALL CAPS reads as shouting."
        }
        if ("!!" in text || "?!" in text) score += 10
        if ("??" in text) score += 8

        if (bareCommand.containsMatchIn(text) && !politeMarkers.containsMatchIn(text)) {
            score += 15
            categories += "missing_niceties"
            missing += "Soften the request with \"could you\" or \"please\""
            if (reason.isEmpty()) reason = "A bare command can sound bossy."
        }
        for (cue in missedCues(request)) {
            score += cue.weight
            categories += cue.category
            missing += cue.tip
            if (reason.isEmpty()) reason = "The reply doesn't respond to what they just said."
        }
        val lastTheirs = request.screenContext.lines().lastOrNull { it.startsWith("[left]") }.orEmpty()
        if (lastTheirs.length > 120 && text.trim().split(Regex("""\s+""")).size <= 2) {
            score += 15
            categories += "standoffish"
            missing += "Match their effort with a fuller reply"
            if (reason.isEmpty()) reason = "A one-word reply to a long message can feel cold."
        }
        return ToneResult(score.coerceIn(0, 100), categories.distinct(), reason, name, missing.distinct().take(3))
    }

    override suspend fun suggest(request: ToneRequest, analysis: ToneResult): Suggestions {
        val softened = soften(request.text).ifBlank { "Okay." }
        val openers = missedCues(request).take(2).joinToString(" ") { it.opener }
        val polite = if (bareCommand.containsMatchIn(softened) && !politeMarkers.containsMatchIn(softened)) {
            "Could you please " + softened.replaceFirstChar { it.lowercase() }.trimEnd('.', '!') + "?"
        } else {
            softened
        }
        val sentenceEnd = if (polite.last() in ".!?") "" else "."
        return Suggestions(
            rewrites = listOf(
                Rewrite("Light touch", polite),
                Rewrite("Warm", "${openers.ifEmpty { "Thanks for letting me know!" }} $polite"),
                Rewrite("Professional", "Thanks for your message. $polite$sentenceEnd Let me know if you have any questions."),
            ),
            tip = "Offline mode only swaps obvious phrases. Enable Gemini for real rewrites.",
        )
    }

    private fun soften(text: String): String {
        var softened = text
        if (softened.count { it.isUpperCase() } > softened.count { it.isLowerCase() }) {
            softened = softened.lowercase().replaceFirstChar { it.uppercase() }
        }
        softened = softened.replace(Regex("""!{2,}"""), "!").replace(Regex("""\?{2,}|\?!|!\?"""), "?")
        for ((from, to) in softenings) {
            softened = softened.replace(Regex("""(?<![\p{L}'])${Regex.escape(from)}(?![\p{L}'])""", RegexOption.IGNORE_CASE), to)
        }
        // Drop profanity and name-calling ("you idiots") outright.
        for (rule in rules.filter { it.category == "inappropriate" || it.category == "insulting" }) {
            for (regex in rule.regexes) {
                softened = softened.replace(Regex("""(?i)\byou\s+""" + regex.pattern), "").replace(regex, "")
            }
        }
        softened = softened.replace(Regex("""\s{2,}"""), " ").replace(Regex("""\s+([,.!?])"""), "$1").trim()
        return softened.replaceFirstChar { it.uppercase() }
    }
}
