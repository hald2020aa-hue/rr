"""Contract checks; the GitHub Actions Gradle build is the compile test."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "src/eu/kanade/tachiyomi/extension/ar/arabshentai/ArabsHentai.kt").read_text(encoding="utf-8")
FILTERS = (ROOT / "src/eu/kanade/tachiyomi/extension/ar/arabshentai/Filters.kt").read_text(encoding="utf-8")
BUILD = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")


class OverlayContracts(unittest.TestCase):
    def test_four_sources(self):
        self.assertEqual(BUILD.count('    source {'), 4)
        for label in ['هنتاي العرب', 'مانهوا', 'دوجينشي', 'كوميكس']:
            self.assertIn(label, BUILD)

    def test_types_persist_in_catalogue_and_search(self):
        for value in ['manhwa', 'doujinshi', 'comic']:
            self.assertIn('"' + value + '"', SOURCE)
        self.assertIn('addQueryParameter("type", it)', SOURCE)
        self.assertIn('catalogueUrl(page, sort', SOURCE)

    def test_filter_contract(self):
        for option in ['new-manga', 'new_chapter', 'alphabet', 'on-going', 'end', 'genre[]', 'state']:
            self.assertTrue(option in SOURCE + FILTERS, option)
        self.assertIn('"التصنيفات"', FILTERS)
        self.assertIn('"الحالة"', FILTERS)
        self.assertIn('"الترتيب"', FILTERS)

    def test_chapter_image_fallback_and_cookie_policy(self):
        self.assertIn('renderedPage(url)', SOURCE)
        self.assertIn('chapter-list', SOURCE)
        self.assertIn('oneshot-reader', SOURCE)
        self.assertIn('data-lazy-src', SOURCE)
        self.assertIn('Base64.decode', SOURCE)
        self.assertNotIn('script:containsData(const images = [)!!', SOURCE)
        self.assertNotIn('cf_clearance', SOURCE)


if __name__ == '__main__':
    unittest.main()
