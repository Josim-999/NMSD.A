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

Rules that make a short spread; follow every one:
1. Hook in the first second. Say the main keyword in the first line (platforms read speech for search). Scene 1 opens a curiosity gap: a question (strongest), a claim that sounds wrong, or a stake. Under 12 words. No greeting, no intro, no "in this video".
2. One idea, paid off fast. By scene 2 the viewer knows what they get by the end. No list of five tips.
3. Tight pacing: each scene is one spoken line of 4 to 12 words, so the picture changes every 1.5 to 4 seconds.
4. Follow these beats in order:
$beats
5. Honest: every number or factual claim must be listed in "sources" with its URL. Make the viewer feel one thing and end on a question viewers will want to answer in the comments. The last line loops back to the hook.
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
- {"type":"photo","query":"2-4 word photo search in English","wiki":"exact English Wikipedia title if it shows a named person/place/thing, else empty","text":"2-6 words"}
- {"type":"logo","brand":"exact brand name","domain":"brand website, e.g. nvidia.com","text":"2-6 words"}   (when a company, product or app is the subject)
- {"type":"chart","text":"short title","items":["Label: number", "2-5 items, numbers only after the colon"]}   (comparing amounts or growth)
- {"type":"timeline","text":"short title","items":["Year — short event", "2-4 items in order"]}
- {"type":"quote","text":"the quote, under 15 words","label":"who said it","wiki":"their English Wikipedia title"}   (only real, accurately quoted words)
- {"type":"outro","text":"the comment question, short","emoji":"one emoji"}  (use for the last scene)
Vary the visuals; never use the same type more than twice in a row. On-screen text must be short.
Real pictures are found automatically: use "photo" for 2 or 3 scenes where a real image helps (people, places, objects, events), with a concrete visual query${if (stockFootage) "; these can play as real stock video" else ""}.
Whenever a line names a company, product, app or organisation, either use the "logo" visual for it, or add "brand" and "domain" to that scene's visual so its logo appears as a badge. Use each brand's real name and website.

Reply with one JSON object and nothing else, exactly this shape:
{
  "title": "working title",
  "one_idea": "the single idea of the video, in one sentence",
  "comment_question": "the question that invites comments",
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
