// Nuvio Z Reaction Link - YouTube pages.
//
// Two jobs: pause and mute the page's own player when Nuvio Z plays that video instead, and report
// when the page moves to another video without a full load (YouTube navigates in-page).
const videoElement = () => document.querySelector("video.html5-main-video") || document.querySelector("video");
const currentVideoId = () => new URL(location.href).searchParams.get("v");

browser.runtime.onMessage.addListener(message => {
  if (message.op !== "quiet") return;
  const video = videoElement();
  if (!video) return Promise.resolve({ ok: false });
  video.muted = true;
  video.pause();
  return Promise.resolve({ ok: true });
});

let reported = currentVideoId();
document.addEventListener("yt-navigate-finish", () => {
  const id = currentVideoId();
  if (!id || id === reported) return;
  reported = id;
  browser.runtime.sendMessage({ type: "videoChanged", url: location.href, videoId: id });
});
