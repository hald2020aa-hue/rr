import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "XHamster Arabic"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://ara.xhamster.com"
    }
}
