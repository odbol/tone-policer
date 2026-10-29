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

    override suspend fun status() = EngineStatus.Ready

    override suspend fun analyze(request: ToneRequest): ToneResult {
        val text = request.text
        var score = 0
        val categories = mutableListOf<String>()
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
        return ToneResult(score.coerceIn(0, 100), categories.distinct(), reason, name)
    }

    override suspend fun suggest(request: ToneRequest, analysis: ToneResult): Suggestions {
        var softened = request.text
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
        softened = softened.replaceFirstChar { it.uppercase() }

        val rewrites = listOf(
            softened,
            "Thanks for your patience. $softened",
            "I might be missing something, but " + softened.replaceFirstChar { it.lowercase() },
        ).distinct()
        return Suggestions(
            rewrites,
            tip = "Offline mode only swaps obvious phrases. Enable Gemini for real rewrites.",
        )
    }
}
