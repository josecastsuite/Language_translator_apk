package com.yusuf.ceviri

enum class Lang(val whisper: String, val nllb: String, val label: String) {
    TR("tr", "tur_Latn", "Türkçe"),
    ID("id", "ind_Latn", "Endonezce"),
    EN("en", "eng_Latn", "İngilizce");

    companion object {
        /** Whisper dil kodunu ("tr", "<|tr|>" vb.) sade koda çevirir. */
        fun clean(code: String): String =
            code.trim().removePrefix("<|").removeSuffix("|>").lowercase()

        /**
         * Whisper'ın bulduğu dili izin verilen dillerden birine eşler.
         * Malayca/Cava/Sunda Endonezceyle, Azerice Türkçeyle aynı aileden; Whisper kısa
         * cümlelerde bunları sık karıştırır. Eşleme yalnız izin verilen dillere yapılır,
         * aksi halde null döner (arayüz "dil anlaşılamadı" der).
         */
        fun fromWhisper(code: String, allowed: Collection<Lang>): Lang? {
            val c = clean(code)
            val lang = entries.firstOrNull { it.whisper == c } ?: when (c) {
                "ms", "jw", "su" -> ID
                "az" -> TR
                else -> null
            }
            return lang?.takeIf { it in allowed }
        }
    }
}

data class Voice(val id: String, val lang: Lang, val label: String)

object Voices {
    val all = listOf(
        Voice("tr_TR-fettah-medium", Lang.TR, "Fettah"),
        Voice("tr_TR-dfki-medium", Lang.TR, "DFKI"),
        Voice("id_ID-news_tts-medium", Lang.ID, "News"),
        Voice("en_US-hfc_female-medium", Lang.EN, "Kadın (HFC)"),
        Voice("en_US-hfc_male-medium", Lang.EN, "Erkek (HFC)"),
    )

    fun forLang(lang: Lang): List<Voice> = all.filter { it.lang == lang }
}
