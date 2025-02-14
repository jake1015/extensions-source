package eu.kanade.tachiyomi.extension.en.topmanhua

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import java.text.SimpleDateFormat
import java.util.Locale

class TopManhua : Madara("Top Manhua", "https://topmanhua.com", "en", SimpleDateFormat("MM/dd/yy", Locale.US)) {
    override val client = super.client.newBuilder()
        .rateLimit(2)
        .build()

    // The website does not flag the content.
    override val filterNonMangaItems = false

    override val mangaSubString = "manhua"

    override fun searchPage(page: Int): String = if (page == 1) "" else "page/$page/"
}
