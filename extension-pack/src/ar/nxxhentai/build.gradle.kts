import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "NXXHentai"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://nxxhentai.net"
    }
}
