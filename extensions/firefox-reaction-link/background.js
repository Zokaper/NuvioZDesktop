// Nuvio Z Reaction Link - background.
//
// Connects to Nuvio Z on 127.0.0.1 (the first of these ports it answers on), tells it which YouTube
// tabs are open, and pauses/mutes a tab when Nuvio Z takes over playing that video. Nuvio Z checks
// that the connection comes from an extension origin; nothing here talks to anything else.
const PORTS = [47123, 47124, 47125, 47126];
let socket = null;
let attempt = 0;

const youTubeId = url => {
  try {
    const u = new URL(url);
    if (u.hostname.endsWith("youtu.be")) return u.pathname.slice(1) || null;
    return u.searchParams.get("v");
  } catch (_) {
    return null;
  }
};

const describe = tab => ({
  tabId: tab.id,
  url: tab.url,
  title: tab.title || "",
  videoId: youTubeId(tab.url || ""),
  incognito: Boolean(tab.incognito),
});

async function youTubeTabs() {
  const tabs = await browser.tabs.query({ url: ["*://*.youtube.com/*", "*://youtu.be/*"] });
  // Private windows are never shared with Nuvio Z.
  return tabs.filter(t => !t.incognito && youTubeId(t.url || "")).map(describe);
}

function send(message) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

async function sendTabs(type) {
  send({ type, tabs: await youTubeTabs() });
}

function connect() {
  const port = PORTS[attempt % PORTS.length];
  attempt++;
  let ws;
  try {
    ws = new WebSocket(`ws://127.0.0.1:${port}/reaction-link`);
  } catch (_) {
    return retry();
  }
  ws.onopen = () => {
    socket = ws;
    attempt = PORTS.indexOf(port); // stick to the port that answered
    sendTabs("hello");
  };
  ws.onclose = () => {
    if (socket === ws) socket = null;
    retry();
  };
  ws.onerror = () => {};
  ws.onmessage = async event => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (_) {
      return;
    }
    if (message.type === "listTabs") return sendTabs("tabs");
    if (message.type === "quiet" && Number.isInteger(message.tabId)) {
      try {
        await browser.tabs.sendMessage(message.tabId, { op: "quiet" });
      } catch (_) {
        // The page is still loading; its content script quiets itself once Nuvio Z asks again.
      }
    }
  };
}

function retry() {
  // Nuvio Z is often simply not running: back off to a slow poll after a full round of ports.
  const delay = attempt % PORTS.length === 0 ? 5000 : 300;
  setTimeout(connect, delay);
}

browser.tabs.onUpdated.addListener((tabId, change, tab) => {
  if (tab.incognito) return;
  if (change.url) {
    const videoId = youTubeId(change.url);
    if (videoId) send({ type: "videoChanged", ...describe(tab), url: change.url, videoId });
  }
  if (change.url || change.title || change.status === "complete") sendTabs("tabs");
});
browser.tabs.onRemoved.addListener(tabId => send({ type: "tabClosed", tabId }));
browser.runtime.onMessage.addListener((message, sender) => {
  if (message.type === "videoChanged" && sender.tab && !sender.tab.incognito) {
    send({ type: "videoChanged", ...describe(sender.tab), url: message.url, videoId: message.videoId });
  }
});

connect();
