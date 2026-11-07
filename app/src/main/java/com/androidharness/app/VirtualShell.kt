package com.androidharness.app

/**
 * Visual-only remote shell modeled on the native AndroidHarness Compose UI.
 * This phase intentionally contains no native feature/API wiring.
 */
internal object VirtualShell {
    val html: String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="theme-color" content="#111216">
<title>AndroidHarness</title>
<style>
:root{color-scheme:dark;--bg:#111216;--surface:#202126;--surface2:#292a30;--selected:#41465b;--lav:#b7c5ff;--text:#e4e4ec;--muted:#b9bac5;--line:#383941;--green:#83d6a6}
*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;background:var(--bg);color:var(--text);font-family:Roboto,"Arial",sans-serif}button,input{font:inherit;color:inherit}button{border:0;cursor:pointer}.app{height:100dvh;display:flex;flex-direction:column;max-width:1100px;margin:auto;background:var(--bg);overflow:hidden}.status{height:34px;padding:5px 22px 0;font-size:14px;font-weight:600;display:flex;justify-content:space-between;letter-spacing:.2px}.top{height:68px;display:flex;align-items:center;padding:0 26px;gap:16px}.appicon{width:48px;height:48px;border-radius:13px;background:#e5e5ee;color:#292a32;display:grid;place-items:center;font-size:24px}.brand{font-size:22px;flex:1}.iconbtn{background:transparent;color:#d0d1da;font-size:26px;padding:8px}.home{flex:1;overflow:auto;padding:10px 26px 0}.mode{display:flex;background:#202126;border-radius:42px;padding:6px;height:94px;margin:10px 0 26px}.mode button{flex:1;border-radius:36px;background:transparent;color:#c5c5cf;font-size:22px}.mode button.active{background:var(--selected);color:#e1e4f4}.primary{height:84px;width:100%;border-radius:20px;background:var(--lav);color:#27334f;font-size:22px;margin-bottom:22px}.search{height:92px;border-radius:20px;background:var(--surface2);display:flex;align-items:center;padding:0 24px;gap:16px;color:#c6c6d0;font-size:20px;margin-bottom:16px}.search input{background:transparent;border:0;outline:0;width:100%;font-size:20px}.switchrow{display:flex;align-items:center;gap:10px;margin-bottom:28px}.switch{height:104px;border-radius:24px;background:#202126;display:flex;padding:6px;flex:1}.switch button{flex:1;border-radius:22px;background:transparent;font-size:21px;color:#c5c5cf}.switch button.active{background:var(--selected);color:#e1e4f4}.sectionlabel{font-size:17px;color:#c4c4ce;margin:28px 10px 18px}.chatrow{min-height:88px;padding:17px 12px;border-radius:18px;display:flex;align-items:center;gap:12px;margin:0 -8px 8px}.chatrow.selected{background:#202127}.chattext{min-width:0;flex:1}.chatname{font-size:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.chatmeta{font-size:16px;color:#b7b8c2;margin-top:7px}.more{font-size:25px;color:#c6c6ce;padding:8px}.bottomnav{border-top:1px solid var(--line);padding:20px 28px 22px;display:grid;gap:8px}.bottomnav button{height:68px;text-align:left;background:transparent;font-size:21px;display:flex;align-items:center;gap:22px;color:#d2d2dc}.glyph{width:28px;text-align:center;font-size:25px}.chatpage{display:none;flex:1;min-height:0;flex-direction:column}.chattoolbar{height:66px;border-bottom:1px solid var(--line);display:flex;align-items:center;padding:0 18px;gap:18px}.chattoolbar .project{font-size:20px;max-width:90px;overflow:hidden;white-space:nowrap}.brain{background:#35467f;color:#c8d5ff;border-radius:16px;padding:12px 16px;font-size:17px}.messages{flex:1;overflow:auto;padding:20px 28px;font-size:20px;line-height:1.62}.messages p{margin:0 0 18px}.messages strong{font-weight:700}.worksummary{height:60px;background:#25262c;border-radius:18px;margin:10px 0 20px;display:flex;align-items:center;padding:0 24px;gap:14px;font-size:17px}.worksummary .check{color:var(--green);font-size:22px}.msgactions{display:flex;align-items:center;gap:30px;color:#c6c6d0;font-size:20px;padding:0 24px 18px}.metrics{padding:0 26px 12px;display:flex;justify-content:space-between;color:#c6c6d0;font-size:14px}.composer{margin:0 18px 18px;border:2px solid #777d9b;border-radius:36px;min-height:88px;display:flex;align-items:center;padding:8px 12px;gap:12px;background:#25262d}.composer button{background:transparent;color:#c7c9d8;font-size:26px}.composer .send{width:68px;height:68px;border-radius:50%;background:#596181;color:#20263a;font-size:30px}.composer input{background:transparent;border:0;outline:0;flex:1;min-width:0;font-size:19px}.drawer{display:none;position:absolute;inset:34px 0 0;background:#111216;z-index:5;padding:22px 26px;overflow:auto}.drawer.open{display:block}.drawer h3{font-weight:500;color:#c4c4ce}.drawer button{display:block;width:100%;text-align:left;padding:20px 8px;background:transparent;font-size:20px;border-bottom:1px solid #292a30}.placeholder{display:none;flex:1;padding:28px;font-size:22px}.placeholder h2{font-size:25px;font-weight:500}.placeholder p{color:var(--muted);font-size:17px}.desktop{display:none}
@media(min-width:800px){.app{max-width:none}.home{max-width:760px;width:100%;margin:auto}.bottomnav{display:none}.desktop{display:flex;position:absolute;left:0;top:110px;bottom:0;width:250px;border-right:1px solid var(--line);padding:18px;flex-direction:column;gap:8px}.desktop button{background:transparent;text-align:left;padding:16px;font-size:17px;border-radius:12px}.desktop button.active{background:var(--selected)}.home{padding-left:280px;max-width:1050px}.chatpage{max-width:1050px;width:100%;margin:auto}.placeholder{padding-left:290px}.status{height:30px}}
</style>
</head>
<body>
<div class="app">
<div class="status"><span>2:52 ▣</span><span>4G　4G　◉　40% ▰</span></div>
<header class="top"><div class="appicon">›</div><div class="brand">AndroidHarness</div><button class="iconbtn" onclick="showDrawer()">☷</button></header>
<nav class="desktop" id="desktopNav">
<button class="active" onclick="showHome()">⌂　Home</button><button onclick="showPage('Tools')">▦　Tools</button><button onclick="showPage('Providers')">♧　Providers</button><button onclick="showPage('Settings')">⚙　Settings</button>
</nav>
<section class="home" id="home">
<div class="mode"><button class="active" id="codeMode" onclick="setMode('Code')">‹ ›　Code</button><button id="chatMode" onclick="setMode('Chat')">▢　Chat</button></div>
<button class="primary" onclick="showConversation()">☷　New <span id="newMode">Code</span> chat</button>
<div class="search"><span>⌕</span><input placeholder="Search chats and messages"></div>
<div class="switchrow"><div class="switch"><button class="active" id="chatsTab" onclick="setList('Chats')">Chats</button><button id="projectsTab" onclick="setList('Projects')">Projects</button></div><button class="iconbtn">▣</button></div>
<div class="sectionlabel" id="recentTitle">Recent Code chats</div>
<div id="chatList">
<div class="chatrow selected"><div class="chattext"><div class="chatname">I test it but it have more bugs I want yo...</div><div class="chatmeta">App workspace · 1h ago</div></div><span style="color:var(--lav)">✓</span><button class="more">•••</button></div>
<div class="chatrow"><div class="chattext"><div class="chatname">Hey</div><div class="chatmeta">App workspace · 9h ago</div></div><button class="more">•••</button></div>
<div class="chatrow"><div class="chattext"><div class="chatname">Hey</div><div class="chatmeta">App workspace · 9h ago</div></div><button class="more">•••</button></div>
<div class="chatrow"><div class="chattext"><div class="chatname">Create a clone of genshin impact</div><div class="chatmeta">App workspace · 9h ago</div></div><button class="more">•••</button></div>
</div>
</section>
<section class="chatpage" id="conversation">
<div class="chattoolbar"><button class="iconbtn" onclick="showHome()">☰</button><span class="project">f...⌄</span><span class="brain">♧ On</span><button class="iconbtn">▱</button><button class="iconbtn">◉</button><button class="iconbtn">◖</button><button class="iconbtn">⋮</button></div>
<div class="messages">
<p>verified, what remains, then wait for your go-ahead before the next.</p>
<p><strong>Recommended order (matching your spec's dependencies):</strong></p>
<p>•　Phase 1 — quota/fallback (already partially started: finish this first)<br>•　Phase 6 — startup/perf (small, low-risk, improves dev speed)<br>•　Phase 5 — floating assistant (builds on Phase 1's provider wiring)<br>•　Phase 3 — persistent AI team (core state)<br>•　Phase 4 — remote browser companion (depends on Phase 3)<br>•　Phase 2 — free providers (can run in parallel with Phase 3)</p>
<p>Want me to authorize myself to start Phase 1's remaining work now, or do you want to run the build first to confirm the 4 edits compile?</p>
<div class="worksummary"><span class="check">✓✓</span><span>Work summary</span><span style="margin-left:auto;color:var(--muted)">3 / 3　›</span></div>
</div>
<div class="msgactions"><span>▢</span><span>◖</span><span>•••</span><span style="margin-left:auto;font-size:14px">19s · 1:33 PM</span></div>
<div class="metrics"><span>133 tk/s</span><span>🟢 Cache hit · 98.73%⌄</span></div>
<div class="composer"><button>＋</button><button>◉</button><input placeholder="Message..."><button>♩</button><button class="send">↑</button></div>
</section>
<section class="placeholder" id="placeholder"><h2 id="pageTitle">Tools</h2><p>AndroidHarness <span id="pageName">Tools</span> screen shell</p></section>
<nav class="bottomnav"><button onclick="showPage('Tools')"><span class="glyph">▦</span>Tools　⌄</button><button onclick="showPage('Providers')"><span class="glyph">♧</span>Providers</button><button onclick="showPage('Settings')"><span class="glyph">⚙</span>Settings</button></nav>
<div class="drawer" id="drawer"><h3>AndroidHarness</h3><button onclick="showHome()">⌂　Home / Chats</button><button onclick="showPage('Tools')">▦　Tools</button><button onclick="showPage('Providers')">♧　Providers</button><button onclick="showPage('Settings')">⚙　Settings</button><button onclick="showPage('Projects')">▤　Projects</button></div>
</div>
<script>
let mode='Code',list='Chats';
function setMode(v){mode=v;document.getElementById('codeMode').classList.toggle('active',v==='Code');document.getElementById('chatMode').classList.toggle('active',v==='Chat');document.getElementById('newMode').textContent=v;document.getElementById('recentTitle').textContent='Recent '+v+' chats'}
function setList(v){list=v;document.getElementById('chatsTab').classList.toggle('active',v==='Chats');document.getElementById('projectsTab').classList.toggle('active',v==='Projects');document.getElementById('recentTitle').textContent=v==='Chats'?'Recent '+mode+' chats':'Projects';document.getElementById('chatList').style.opacity=v==='Chats'?'1':'.35'}
function showHome(){document.getElementById('home').style.display='block';document.getElementById('conversation').style.display='none';document.getElementById('placeholder').style.display='none';document.getElementById('drawer').classList.remove('open')}
function showConversation(){document.getElementById('home').style.display='none';document.getElementById('conversation').style.display='flex';document.getElementById('placeholder').style.display='none';document.getElementById('drawer').classList.remove('open')}
function showPage(v){document.getElementById('home').style.display='none';document.getElementById('conversation').style.display='none';document.getElementById('placeholder').style.display='block';document.getElementById('pageTitle').textContent=v;document.getElementById('pageName').textContent=v;document.getElementById('drawer').classList.remove('open')}
function showDrawer(){document.getElementById('drawer').classList.toggle('open')}
</script>
</body>
</html>
""".trimIndent()
}
