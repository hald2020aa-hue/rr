#!/usr/bin/env python3
"""Compatibility fixes for external anime repositories on pinned Aniyomi.

- Legacy repo.json may omit optional index_v2 (Goddivor).
- Github HTML raw paths should use raw.githubusercontent.com (Yuzono).
- Attempt legacy extension-lib 13 support without relaxing signer trust or APK
  signature matching. Runtime API compatibility is NOT guaranteed.
"""
from pathlib import Path


def apply_external_repo_compat_patch(root: Path, replace_once) -> None:
    legacy = "data/src/main/java/mihon/data/extension/anime/model/NetworkLegacyAnimeExtensionRepo.kt"
    service = "data/src/main/java/mihon/data/extension/anime/service/AnimeExtensionStoreService.kt"
    loader = "app/src/main/java/eu/kanade/tachiyomi/extension/anime/util/AnimeExtensionLoader.kt"

    # In kotlinx.serialization, nullable fields without defaults are still REQUIRED.
    # Goddivor's repo.json doesn't include index_v2, so it otherwise throws.
    replace_once(
        legacy,
        '    val indexV2: String?,\n',
        '    val indexV2: String? = null,\n',
    )

    # The user-provided GitHub /raw/ URL can redirect differently depending on
    # requester. Normalize ONLY these known data endpoints, preserving signatures.
    replace_once(
        service,
        '    suspend fun fetch(indexUrl: String): Result<AnimeExtensionStore> {\n',
        '''    private fun normalizeIndexUrl(url: String): String = when (url) {
        "https://github.com/yuzono/anime-repo/raw/repo/index.pb" ->
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.pb"
        "https://github.com/yuzono/anime-repo/raw/repo/index.min.json" ->
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
        "https://github.com/yuzono/anime-repo/raw/repo/repo.json" ->
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/repo.json"
        else -> url
    }

    suspend fun fetch(indexUrl: String): Result<AnimeExtensionStore> {
''',
    )
    replace_once(
        service,
        '            val response = network.client.newCall(GET(updatedIndexUrl)).awaitSuccess()',
        '            val response = network.client.newCall(GET(normalizeIndexUrl(updatedIndexUrl))).awaitSuccess()',
    )
    replace_once(
        service,
        '                        network.client.newCall(GET(updatedIndexUrl)).awaitSuccess().body.source().use {',
        '                        network.client.newCall(GET(normalizeIndexUrl(updatedIndexUrl))).awaitSuccess().body.source().use {',
    )
    replace_once(
        service,
        'network.client.newCall(GET(store.extensionListUrl!!)).awaitSuccess()',
        'network.client.newCall(GET(normalizeIndexUrl(store.extensionListUrl!!))).awaitSuccess()',
    )
    replace_once(
        service,
        'network.client.newCall(GET(store.indexUrl)).awaitSuccess()',
        'network.client.newCall(GET(normalizeIndexUrl(store.indexUrl))).awaitSuccess()',
    )

    # Fetch index.min.json from the canonical raw endpoint when a legacy repo
    # was originally registered using the GitHub /raw/ URL.
    replace_once(
        service,
        'network.client.newCall(GET("$storeBaseUrl/index.min.json")).awaitSuccess()',
        'network.client.newCall(GET(normalizeIndexUrl("$storeBaseUrl/index.min.json"))).awaitSuccess()',
    )

    # Pornhub's external APK is versionName=13.20 (libVersion 13). The previous
    # loader rejects it before class loading, so let older APIs be attempted.
    # All existing trust/signing checks stay intact, and incompatible v13
    # implementations will still fail safely at class loading / invocation.
    replace_once(
        loader,
        '    private val SUPPORTED_LIB_VERSIONS = listOf(14.0, 16.0, 17.0)\n',
        '    private val SUPPORTED_LIB_VERSIONS = listOf(13.0, 14.0, 16.0, 17.0)\n',
    )
    print(' + Legacy repo.json optional index_v2 handled')
    print(' + Yuzono /raw/ endpoints normalized for Protobuf/JSON repository data')
    print(' + Extension-lib 13 load attempt enabled (signature checks UNCHANGED)')
