/* Qari anime-only Gecko bridge. Reads visible DOM of pages opened normally by
 * the user. No credential, cookie, Cloudflare-token, or network interception.
 * This script never catalogs manga or manhwa.
 */
(() => {
  "use strict";
  const host = location.hostname.toLowerCase();
  if (host !== "arabshentai.com" && host !== "www.arabshentai.com") return;

  const decodedPath = (() => {
    try { return decodeURIComponent(location.pathname).toLowerCase(); }
    catch { return location.pathname.toLowerCase(); }
  })();
  const isManga = /(?:^|\/)(?:manga|manhwa)(?:\/|$)/i.test(decodedPath) ||
    /مانجا|مانهوا|تصنيفات\//i.test(decodedPath) ||
    new URLSearchParams(location.search).get("type") === "manhwa";
  if (isManga) return;

  const isArchive = decodedPath.replace(/\/+$/, "") === "/anime" ||
    /^\/anime\/page\/\d+\/?$/.test(decodedPath) ||
    decodedPath.includes("تصنيفات-الأنمي");

  let previous = "";
  let pending = null;
  const blockedPaths = /(?:^|\/)(?:manga|manhwa|tag|category|page|login|register|privacy|contact)(?:\/|$)/i;
  const blockedWords = /مانجا|مانهوا|manga|manhwa/i;

  function allowed(url) {
    if (url.protocol !== "https:") return false;
    if (url.hostname !== "arabshentai.com" && url.hostname !== "www.arabshentai.com") return false;
    let path;
    try { path = decodeURIComponent(url.pathname).toLowerCase(); }
    catch { path = url.pathname.toLowerCase(); }
    if (blockedPaths.test(path) || blockedWords.test(path)) return false;
    if (path.includes("تصنيفات") || path === "/" || path === "/anime/") return false;
    if (path === "/anime") return false;
    return true;
  }

  function clean(text) {
    return (text || "").replace(/\s+/g, " ").trim();
  }

  function absolute(raw) {
    try { return new URL(raw, location.href); }
    catch { return null; }
  }

  function coverFrom(element) {
    const img = element?.querySelector("img");
    if (!img) return "";
    const raw = img.getAttribute("data-src") || img.getAttribute("data-original") ||
      img.getAttribute("data-lazy-src") || img.currentSrc || img.getAttribute("src");
    const url = raw ? absolute(raw) : null;
    return url?.protocol === "https:" ? url.href : "";
  }

  function isCard(anchor, card) {
    if (anchor.querySelector("img")) return true;
    if (card && card !== anchor && card.querySelector("img")) return true;
    return false;
  }

  function findCards() {
    const collected = new Map();
    const selectors = [
      "article a[href]", ".anime-item a[href]", ".video-item a[href]",
      ".poster a[href]", ".thumb a[href]", ".post a[href]",
      ".item a[href]", ".card a[href]", "a[href]"
    ].join(",");
    for (const anchor of document.querySelectorAll(selectors)) {
      const destination = absolute(anchor.href);
      if (!destination || !allowed(destination)) continue;
      const card = anchor.closest("article,.anime-item,.video-item,.poster,.thumb,.post,.item,.card,.film-item") || anchor;
      if (!isCard(anchor, card)) continue;
      const title = clean(card.querySelector("h2,h3,h4,.title,.name,.entry-title")?.textContent) ||
        clean(anchor.getAttribute("title")) ||
        clean(anchor.querySelector("img")?.getAttribute("alt")) ||
        clean(card.querySelector("img")?.getAttribute("alt")) ||
        clean(anchor.textContent);
      if (title.length < 3 || title.length > 220 || blockedWords.test(title)) continue;
      destination.hash = "";
      const cover = coverFrom(card) || coverFrom(anchor);
      if (!cover) continue; // avoids site navigation links masquerading as titles
      collected.set(destination.href, {url: destination.href, title, cover});
      if (collected.size >= 90) break;
    }
    return [...collected.values()];
  }

  function findEpisodes() {
    const collected = new Map();
    const selectors = ".episodes a[href],.episode-list a[href],.seasons a[href],a[href*='episode'],a[href*='watch'],a[href]";
    for (const anchor of document.querySelectorAll(selectors)) {
      const destination = absolute(anchor.href);
      if (!destination || !allowed(destination)) continue;
      const label = clean(anchor.textContent) || clean(anchor.getAttribute("title"));
      if (!/(?:episode|watch|الحلقة|حلقة|مشاهدة|الجزء|part)/i.test(destination.pathname + " " + label)) continue;
      if (label.length < 2 || label.length > 200) continue;
      if (destination.href === location.href) continue;
      collected.set(destination.href, {url: destination.href, title: label});
      if (collected.size >= 1000) break;
    }
    return [...collected.values()];
  }

  function emit(type, payload) {
    browser.runtime.sendNativeMessage("qariSource", {
      type, url: location.href, ...payload
    }).catch(() => {});
  }

  // WordPress-style pagination. Follow ONLY links already present in the page;
  // do not invent undocumented URLs or leave the anime directory.
  function archivePagination() {
    const here = new URL(location.href);
    const parseNumber = (url) => {
      const match = url.pathname.match(/\/anime\/page\/(\d+)\/?$/i) ||
        url.pathname.match(/\/page\/(\d+)\/?$/i);
      const query = Number(url.searchParams.get("paged") || url.searchParams.get("page"));
      return match ? Number(match[1]) : (query > 0 ? query : 1);
    };
    const current = parseNumber(here);
    let total = current;
    const candidates = [];
    // Some site themes render numbered links without standard pagination CSS.
    const anchors = document.querySelectorAll("a[href]");
    for (const a of anchors) {
      const url = absolute(a.href);
      if (!url || url.protocol !== "https:" || !["arabshentai.com", "www.arabshentai.com"].includes(url.hostname)) continue;
      const normalizedPath = (() => { try { return decodeURIComponent(url.pathname).toLowerCase(); } catch { return ""; } })();
      const isArchivePage = /^\/anime\/page\/\d+\/?$/.test(normalizedPath) ||
        normalizedPath === "/anime/" || normalizedPath === "/anime";
      if (!isArchivePage) continue;
      const n = parseNumber(url);
      const label = clean(a.textContent || a.getAttribute("aria-label") || "");
      const pageLabel = /^\d+$/.test(label) ? Number(label) : 0;
      total = Math.max(total, n, pageLabel);
      if (n > current && n < 1000) candidates.push({n, url: url.href});
    }
    const display = document.body?.innerText?.match(/(?:صفحة|page)\s*(\d+)\s*(?:من|of)\s*(\d+)/i);
    if (display) total = Math.max(total, Number(display[2]));
    candidates.sort((a,b) => a.n-b.n);
    return {current, total: Math.min(total, 500), nextUrl: candidates[0]?.url || ""};
  }

  function snapshot() {
    const pageTitle = (document.title || "").toLowerCase();
    if (pageTitle.includes("just a moment") || document.querySelector("#challenge-form, #cf-challenge-running")) return;
    if (!document.body) return;

    if (isArchive) {
      const items = findCards();
      if (items.length) {
        const pagination = archivePagination();
        const stamp = `catalog|${location.href}|${items.map(i => i.url).join("|")}`;
        if (stamp !== previous) {
          previous = stamp;
          emit("qari-anime-catalog", {items, ...pagination});
        }
      }
      const links = items.slice(0, 60).map(item => ({
        label: item.title, url: item.url, kind: "title"
      }));
      if (links.length) emit("qari-source-links", {links});
    } else if (allowed(new URL(location.href))) {
      const heading = clean(document.querySelector("h1,.entry-title,.post-title")?.textContent);
      const description = clean(document.querySelector(".description,.synopsis,.story,.entry-content p,.post-content p")?.textContent);
      const episodes = findEpisodes();
      const stamp = `detail|${location.href}|${episodes.map(e => e.url).join("|")}`;
      if (stamp !== previous) {
        previous = stamp;
        emit("qari-anime-detail", {detail: {title: heading, description, episodes}});
      }
      if (episodes.length) {
        emit("qari-source-links", {links: episodes.slice(0, 60).map(e => ({
          label: e.title, url: e.url, kind: "episode"
        }))});
      }
    }
  }

  function schedule() {
    if (pending !== null) clearTimeout(pending);
    pending = setTimeout(() => { pending = null; snapshot(); }, 1300);
  }
  schedule();
  const observer = new MutationObserver(schedule);
  observer.observe(document.documentElement, {childList: true, subtree: true});
  addEventListener("pagehide", () => {
    observer.disconnect();
    if (pending !== null) clearTimeout(pending);
  }, {once: true});
})();
