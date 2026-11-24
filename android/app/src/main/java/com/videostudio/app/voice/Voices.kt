package com.videostudio.app.voice

import java.io.File
import java.util.Locale

class VoiceError(message: String) : Exception(message)

data class Word(val text: String, val start: Double, val end: Double)

data class VoiceResult(val audio: File, val words: List<Word>, val seconds: Double, val voice: String)

data class VoiceOption(val id: String, val label: String, val language: String, val locale: Locale)

object Voices {
    val all = listOf(
        VoiceOption("en-US-AndrewNeural", "Andrew — US male, warm", "English", Locale.US),
        VoiceOption("en-US-BrianNeural", "Brian — US male, casual", "English", Locale.US),
        VoiceOption("en-US-AvaNeural", "Ava — US female, friendly", "English", Locale.US),
        VoiceOption("en-US-EmmaNeural", "Emma — US female, cheerful", "English", Locale.US),
        VoiceOption("en-US-ChristopherNeural", "Christopher — US male, deep", "English", Locale.US),
        VoiceOption("en-GB-RyanNeural", "Ryan — UK male", "English", Locale.UK),
        VoiceOption("en-GB-SoniaNeural", "Sonia — UK female", "English", Locale.UK),
        VoiceOption("en-IN-PrabhatNeural", "Prabhat — India male", "English", Locale("en", "IN")),
        VoiceOption("en-IN-NeerjaNeural", "Neerja — India female", "English", Locale("en", "IN")),
        VoiceOption("bn-BD-PradeepNeural", "Pradeep — Bangla male", "Bangla", Locale("bn", "BD")),
        VoiceOption("bn-BD-NabanitaNeural", "Nabanita — Bangla female", "Bangla", Locale("bn", "BD")),
        VoiceOption("hi-IN-MadhurNeural", "Madhur — Hindi male", "Hindi", Locale("hi", "IN")),
        VoiceOption("hi-IN-SwaraNeural", "Swara — Hindi female", "Hindi", Locale("hi", "IN")),
        VoiceOption("ur-PK-AsadNeural", "Asad — Urdu male", "Urdu", Locale("ur", "PK")),
        VoiceOption("ar-SA-HamedNeural", "Hamed — Arabic male", "Arabic", Locale("ar", "SA")),
        VoiceOption("es-ES-AlvaroNeural", "Alvaro — Spanish male", "Spanish", Locale("es", "ES")),
        VoiceOption("fr-FR-HenriNeural", "Henri — French male", "French", Locale.FRANCE),
        VoiceOption("de-DE-ConradNeural", "Conrad — German male", "German", Locale.GERMANY),
        VoiceOption("pt-BR-AntonioNeural", "Antonio — Portuguese (BR) male", "Portuguese", Locale("pt", "BR")),
        VoiceOption("id-ID-ArdiNeural", "Ardi — Indonesian male", "Indonesian", Locale("id", "ID")),
    )

    val languages: List<String> = all.map { it.language }.distinct()

    fun find(id: String): VoiceOption = all.firstOrNull { it.id == id } ?: all.first()

    fun forLanguage(language: String): List<VoiceOption> = all.filter { it.language == language }.ifEmpty { all }

    /** "+8%" -> 1.08, for the phone's own voice. */
    fun rateFactor(rate: String): Float =
        ((rate.trim().removeSuffix("%").toFloatOrNull() ?: 0f) / 100f + 1f).coerceIn(0.5f, 2f)
}
