package com.videostudio.app.ai

import com.videostudio.app.data.Project

object Prompts {
    const val WORDS_PER_SECOND = 2.9

    val BEATS = listOf(
        "HOOK (first line, about 2 s): a curiosity gap the viewer must close: a question, a claim that sounds wrong, or a stake. Under 12 words. No greeting, no intro, no 'in this video'.",
        "PROMISE (by about 3 s, line 2 at the latest): say what the viewer will know by the end.",
        "PROOF (most of the video): one idea only, shown in beats of 3 to 5 seconds: escalate, break a myth, give a specific fact with its source.",
        "PAYOFF (the last 10-15 s): the answer that closes the loop the hook opened.",
        "TRIGGER (last lines): one line that makes the viewer feel something (surprise, a little fear, the urge to argue) and one that loops back to the hook, so the replay feels seamless.",
    )

    fun research(p: Project): String {
        val audience = if (p.audience.isBlank()) "" else "Audience: ${p.audience}"
        return """
You are a researcher preparing facts for a 30 to 60 second vertical video.
Topic: ${p.topic}
$audience
Use Google Search. Find:
1. The single most surprising, specific angle on this topic that would make a viewer stop scrolling.
2. Eight to twelve concrete, checkable facts (numbers, dates, named studies, named tools), each with the URL of the page it came from.
3. Common myths or mistakes people believe about it.
Write plain notes in English, one fact per line in the form: FACT — source URL.
Do not invent anything; leave out any fact you could not find a source for.
""".trim()
    }

    fun script(
        p: Project,
        seriesName: String,
        seriesPromise: String,
        extraHashtags: String,
        feedback: String = "",
        current: String = "",
        stockFootage: Boolean = false,
        take: String = "",
    ): String {
        val maxWords = (p.lengthSec * WORDS_PER_SECOND).toInt()
        val research = if (p.researchNotes.isNotBlank())
            "The research is below. Use only facts listed in it for numbers and claims.\n<research>\n${p.researchNotes}\n</research>"
        else
            "No research was run. Use only facts you are confident are true and well known; avoid exact numbers you are unsure of."
        val rewrite = if (feedback.isBlank() || current.isBlank()) "" else
            "\nThis is a rewrite. The current script is:\n<current_script>\n$current\n</current_script>\nThe user asked for this change: $feedback\n"
        val series = if (seriesName.isNotBlank()) "Series: \"$seriesName\" — $seriesPromise" else "Series promise: $seriesPromise"
        val beats = BEATS.joinToString("\n") { "   - $it" }
        return """
You are a scriptwriter with years of short-form video behind you. Write the script for one vertical short
(YouTube Shorts, TikTok, Instagram Reels).

Topic: ${p.topic}
Audience: ${p.audience.ifBlank { "work it out from the topic" }}
Language of the spoken lines and on-screen text: ${p.language}
Target length: ${p.lengthSec} seconds spoken: write ${maxWords - 8} to $maxWords words in total (the voice reads about 2.9 words a second).
$series
$rewrite
$research

THE SCRIPT PLAYBOOK (what top educational Shorts do). Follow every rule.

A. HOOK (scene 1, the first 1-2 seconds) decides everything.
- First write 5 hook options in "hook_options", each with a DIFFERENT formula from this list:
  contrarian flip ("Everyone says X. They're wrong."), mistake warning ("Stop doing X if you want Y."),
  list tease ("3 AI tools that... the last one is free."), concrete promise ("Do X in 10 seconds with one prompt."),
  bold claim + proof ("This free tool beat ChatGPT. Here's the test."), curiosity loop ("I asked AI to X. It did something weird."),
  forbidden knowledge ("What AI companies won't tell you about X."), stakes ("You're losing X every day because of Y."),
  question hook (a specific question the viewer cannot answer yet), pattern interrupt (a surprising image or line).
- Score each option 1-10 for curiosity, specificity and relevance; use the BEST one as scene 1.
- The hook: 5 to 10 words, says the main keyword, has a number or a named tool/person when possible, and opens a loop the video closes later.
- Never open with: "Hey guys", "In this video", "Did you know", "Have you ever wondered", "Today we", "Let's talk about", "Welcome".
B. STRUCTURE (for ${p.lengthSec} seconds; scale the timings):
- 0-2 s HOOK. 2-5 s PROMISE/STAKES: what the viewer gets by the end, or what they lose if they skip.
- 5-15 s SETUP: the first concrete fact or example, fast.
- About every 12 seconds a RE-HOOK line that re-opens curiosity ("But here's the part nobody mentions.", "Wait, it gets better.", "This is where most people mess up.").
- Middle: 2 or 3 escalating beats, each one concrete: a number, a named tool, an exact step or prompt, a real example. Each beat is better than the last.
- PAYOFF (last 15 s): closes the hook's loop with the answer or the result. Give ONE thing the viewer can do today.
- END: one line that sparks a comment (a choice, a hot take, "which one would you try?"), optionally a 3-word save line ("Save this for later."), and the very last words flow naturally back into the hook so the replay feels seamless. No "like and subscribe".
C. LANGUAGE:
- Write for a 12-year-old: sentences of 4 to 12 words, one idea each, active voice, present tense, "you" and "your".
- Show, don't tell: every claim gets an example, a number or a name. At least 3 specific numbers, tools or names in the script.
- Banned words and phrases (they sound like AI and kill retention): "basically", "essentially", "in today's world", "game-changer", "game changer", "revolutionary", "unlock", "unleash", "delve", "dive into", "let's dive", "it's important to note", "in conclusion", "whether you're", "the world of", "landscape", "leverage", "seamless", "cutting-edge", "harness", "elevate", "supercharge".
- No lists of more than 3 items. No repeating the same sentence opening twice in a row.
D. Each scene is one spoken line, so the picture changes every 1.5 to 4 seconds. Follow these beats in order:
$beats
E. Honest: every number or factual claim must be listed in "sources" with its URL. Never invent a statistic; if unsure, say it without a number.
Originality matters (platforms refuse to pay for template-like videos): include one line that gives a clear, reasoned take or insight in the creator's voice, not just facts.${if (take.isNotBlank()) " The creator's own take, to work in faithfully: \"$take\"." else ""}
YouTube rules (the channel must stay monetizable), follow every one:
- Advertiser-friendly: no profanity, no graphic, shocking or hateful content, no dangerous instructions.
- The title and first line must honestly match what the video delivers: no clickbait that over-promises.
- Health, money, law, news or elections: no unproven claims, no promises of cures or returns; say what the evidence shows. Never speak as a doctor, lawyer or financial adviser; the voice is a curious explainer.
- Quotes only if they are real and appear in the research; otherwise paraphrase and say who argued it.
- No real person's private life; public facts about public figures only.
Write the way a person talks to a friend. Write numbers in "say" as they are spoken ("seventy percent"), but in the visual use digits ("70%").

For every scene choose ONE visual from this list (the app animates them), and fill only its fields:
- {"type":"hook","text":"3-7 punchy words","emoji":"one emoji"}            (use for scene 1)
- {"type":"stat","value":"70%","label":"short label under the number"}     (a number: %, x, ${'$'}, years...)
- {"type":"list","text":"short title","items":["2-4 short items"]}
- {"type":"steps","text":"short title","items":["2-4 short steps"]}
- {"type":"compare","left":"MYTH","leftText":"short","right":"FACT","rightText":"short"}
- {"type":"statement","text":"5-10 words on screen","highlight":"one word from text"}
- {"type":"emoji","emoji":"one emoji","text":"2-5 words"}
- {"type":"question","text":"short question"}
- {"type":"photo","query":"2-4 word photo search in English","wiki":"exact English Wikipedia title if it shows a named person/place/thing, else empty","text":"2-6 words","library":"nasa for space/planets/rockets/earth-from-space, museum for art/ancient history/artefacts, else empty"}
- {"type":"logo","brand":"exact official brand name","domain":"its official website domain, e.g. openai.com, gemini.google.com","wiki":"its exact English Wikipedia article title","text":"2-6 words"}   (when a company, product or app is the subject)
- {"type":"chart","text":"short title","items":["Label: number", "2-5 items, numbers only after the colon"]}   (comparing amounts or growth)
- {"type":"timeline","text":"short title","items":["Year — short event", "2-4 items in order"]}
- {"type":"quote","text":"the quote, under 15 words","label":"who said it","wiki":"their English Wikipedia title"}   (only real, accurately quoted words)
- {"type":"tier","text":"short title","items":["S: best thing", "A: next", "B: ...", "C: worst", "3-6 items, each 'Tier: name' with tiers S, A, B, C or D"]}   (ranking things: viewers love to argue in the comments)
- {"type":"outro","text":"the comment question, short","emoji":"one emoji"}  (use for the last scene)
Vary the visuals; never use the same type more than twice in a row. On-screen text must be short.
Real pictures are found automatically: use "photo" for 2 or 3 scenes where a real image helps (people, places, objects, events), with a concrete visual query${if (stockFootage) "; these can play as real stock video" else ""}.
Whenever a line names a company, product, app or organisation, either use the "logo" visual for it, or add "brand" and "domain" to that scene's visual so its logo appears as a badge. Logos are checked against the official website, so: use the exact official name ("Google Gemini", not "Gemini"; "Microsoft Copilot", not "Copilot"), the exact official domain of that product (gemini.google.com, chatgpt.com, claude.ai, copilot.microsoft.com), and never put a brand on a scene that does not name it. If you are not sure of the official domain, leave "brand" empty.

Reply with one JSON object and nothing else, exactly this shape:
{
  "title": "working title",
  "one_idea": "the single idea of the video, in one sentence",
  "comment_question": "the question that invites comments",
  "hook_options": [ {"text": "hook option", "formula": "which formula", "score": 8} ],
  "mood": "one of: energetic, calm, dramatic, playful (sets the music)",
  "compliance": {"sensitive": "none, health, finance, news or elections", "made_for_kids": false},
  "scenes": [ {"say": "the spoken words of this scene", "emphasis": "the one most important word of say, copied exactly", "visual": { ...one of the visuals above... }} ],
  "sources": [ {"claim": "...", "url": "..."} ],
  "publish": {
    "titles": ["three title options of 4 to 6 words (20-40 characters), main keyword first"],
    "description": "two or three sentences, the search phrase people type in the first one",
    "hashtags": ["three to five, one broad and the rest specific, without the # sign"]
  }
}
Hashtags the channel always likes (use if they fit): $extraHashtags
""".trim()
    }
}
