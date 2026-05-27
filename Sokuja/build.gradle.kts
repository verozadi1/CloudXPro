// use an integer for version numbers
version = 1

cloudstream {
    description = "Nonton & Download Anime Subtitle Indonesia"

    language = "id"

    authors = listOf("AiCurv")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
        "ONA",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://x5.sokuja.uk&size=%size%"

    isCrossPlatform = false
}
