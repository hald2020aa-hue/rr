/* Qari read-only bridge. No cookies, credentials, HTML, or requests leave Gecko.
 * Captures visible anchors on a page the user has already loaded normally.
 * This is not a Cloudflare bypass or a native extension implementation.
 */
(() => {
  "use strict";
  const host = location.hostname.toLowerCase();
  if (host !== "arabshentai.com" && host !== "www.arabshentai.com") return;
  let previous = "";
  let pending = null;
  function snapshot() {
    const heading = (document.title || "").toLowerCase();
    if (heading.includes("just a moment") || document.querySelector("#challenge-form, #cf-challenge-running")) return;
    const seen = new Set();
    const links = [];
    for (const anchor of document.querySelectorAll("a[href]")) {
      const label = (anchor.innerText || anchor.textContent || "").replace(/\s+/g, " ").trim().slice(0, 100);
      if (label.length < 3) continue;
      let destination;
      try { destination = new URL(anchor.href, location.href); } catch { continue; }
      if (destination.protocol !== "https:") continue;
      if (destination.hostname !== "arabshentai.com" && destination.hostname !== "www.arabshentai.com") continue;
      destination.hash = "";
      if (destination.href === location.href || seen.has(destination.href)) continue;
      if (/\b(login|register|privacy|terms|contact|help|logout)\b/i.test(destination.pathname)) continue;
      seen.add(destination.href);
      const target = destination.pathname + " " + label;
      let kind = "link";
      if (/(episode|watch|chapter|video|الحلقة|حلقة|مشاهدة|الجزء)/i.test(target)) kind = "episode";
      else if (/(anime|series|title|series|show|فيلم|انمي|أنمي|مسلسل)/i.test(target)) kind = "title";
      links.push({ label, url: destination.href, kind });
      if (links.length >= 60) break;
    }
    const fingerprint = `${location.href}|${links.map(x => x.url).join("|")}`;
    if (fingerprint === previous) return;
    previous = fingerprint;
    browser.runtime.sendNativeMessage("qariSource", {
      type: "qari-source-links", url: location.href, links
    }).catch(() => {});
  }
  function schedule() {
    if (pending !== null) clearTimeout(pending);
    pending = setTimeout(() => { pending = null; snapshot(); }, 700);
  }
  schedule();
  const observer = new MutationObserver(schedule);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  addEventListener("pagehide", () => { observer.disconnect(); if (pending !== null) clearTimeout(pending); }, { once: true });
})();
