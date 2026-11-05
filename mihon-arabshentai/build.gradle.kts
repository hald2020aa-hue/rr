import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Arabs Hentai Manga — Mihon"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    // Keep the upstream primary name so existing library source IDs are preserved.
    source {
        name = "هنتاي العرب"
        lang = "ar"
        baseUrl = "https://arabshentai.com"
    }
    source {
        name = "ArabsHentai • مانهوا"
        lang = "ar"
        baseUrl = "https://arabshentai.com"
    }
    source {
        name = "ArabsHentai • دوجينشي"
        lang = "ar"
        baseUrl = "https://arabshentai.com"
    }
    source {
        name = "ArabsHentai • كوميكس"
        lang = "ar"
        baseUrl = "https://arabshentai.com"
    }
}
