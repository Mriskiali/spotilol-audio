package com.project.lol.webview.injections

/*
 * Audio settings — crossfade / streaming quality cap / data saver.
 *
 * WHAT WORKS CLIENT-SIDE:
 *   - crossfade: volume ramp on the media element itself (`el.volume`), NOT Web
 *     Audio. The web player uses MSE and reports `duration === Infinity`, so
 *     duration comes from the progressbar `max` attr when needed. Setting
 *     el.volume cannot mute playback, unlike MediaElementSource routing (which
 *     also breaks when Spotify already owns the element or the AudioContext
 *     starts suspended — both bit us in earlier builds).
 *   - quality CAP (downgrade only): /metadata/4/track/ is wrapped at
 *     document-start; `files` entries above the cap are stripped so the player
 *     falls back by itself. Upgrades are impossible: the server never returns
 *     higher-tier ids for accounts without that tier.
 *   - data saver: CSS (small covers, block video) + forces the cap to 96.
 * Gapless/automix were removed: they are native-client / server-side features
 * with no effect from a WebView (stored flags only mislead the user).
 */
object AudioSettings {
    const val CONTENT = """
        (function(){
            if(window.__splAudioSettings) return; window.__splAudioSettings=true;

            var LS='spotilol.audio.';
            var S={
                crossfade: 0,          // seconds 0..12 (0 = off)
                quality: -1,           // quality CAP: -1 auto, 0=96, 1=160, 2=320
                downloadQuality: 2,
                normalize: false,
                autoAdjust: true,
                dataSaver: false
            };
            try{
                if(window.__splAudioPrefs){
                    var np=JSON.parse(window.__splAudioPrefs);
                    for(var k in np){ if(k in S) S[k]=np[k]; }
                }
                var raw=localStorage.getItem(LS+'v1');
                if(raw){ var p=JSON.parse(raw); for(var k2 in p){ if(k2 in S) S[k2]=p[k2]; } }
            }catch(e){}
            function save(){ try{ localStorage.setItem(LS+'v1',JSON.stringify(S)); }catch(e){} }
            function notify(){ try{ AndBridge.audioPrefsChanged(JSON.stringify(S)); }catch(e){} }
            function dbg(m){ try{ AndBridge.dbg('a',m); }catch(e){} }

            // ---------- QUALITY CAP (real downgrade via metadata files map) ----------
            function capBits(){
                if(S.dataSaver) return 96;
                if(S.quality===0) return 96;
                if(S.quality===1) return 160;
                if(S.quality===2) return 320;
                return 0; // auto / no cap
            }
            // keys look like "mp3_96" / "ogg_vorbis_160" / "aac_256" / "mp3_320"
            function stripFiles(files,cap){
                if(cap<=0||!files) return files;
                var out={},kept=false,bestK=null,bestB=Infinity;
                for(var k in files){
                    var m=/(\d{2,4})\s*$/.exec(k);
                    var b=m?parseInt(m[1],10):0;
                    if(b<=cap){ out[k]=files[k]; kept=true; }
                    if(b>0&&b<bestB){ bestB=b; bestK=k; }
                    dbg('cap '+cap+': '+k+'='+b+(b>cap?' DROP':' keep'));
                }
                if(!kept&&bestK){
                    out={}; out[bestK]=files[bestK];
                    dbg('cap '+cap+' too strict -> lowest '+bestK);
                }
                return out;
            }
            // document-start (SpotifyWebViewClient early payload): wrap before Spotify's code
            try{
                if(!window.__splMetaCapWrapped){
                    window.__splMetaCapWrapped=true;
                    var of=window.fetch.bind(window);
                    window.fetch=function(input,init){
                        var p=of(input,init);
                        try{
                            var url=(typeof input==='string')?input:((input&&input.url)||'');
                            if(url.indexOf('/metadata/4/track/')!==-1){
                                return p.then(function(resp){
                                    try{
                                        var ct=resp.headers.get('content-type')||'';
                                        if(ct.indexOf('json')===-1) return resp;
                                        return resp.clone().json().then(function(j){
                                            try{
                                                var cap=capBits();
                                                if(j&&j.files&&typeof j.files==='object'&&cap>0){
                                                    var before=Object.keys(j.files).length;
                                                    j.files=stripFiles(j.files,cap);
                                                    var after=Object.keys(j.files).length;
                                                    dbg('metadata files '+before+'->'+after+' @cap '+cap);
                                                    return new Response(JSON.stringify(j),{
                                                        status:resp.status,
                                                        statusText:resp.statusText,
                                                        headers:resp.headers
                                                    });
                                                }
                                            }catch(e){}
                                            return resp;
                                        }).catch(function(){ return resp; });
                                    }catch(e){ return resp; }
                                });
                            }
                        }catch(e){}
                        return p;
                    };
                }
            }catch(e){}

            // ---------- DATA SAVER (CSS) ----------
            var dsTimer=null;
            function applyDataSaver(){
                if(dsTimer) clearTimeout(dsTimer);
                dsTimer=setTimeout(function(){
                    var st=document.getElementById('spl-datasaver-css');
                    if(S.dataSaver){
                        if(!st){
                            st=document.createElement('style'); st.id='spl-datasaver-css';
                            st.textContent=
                                'video{display:none!important;}'
                                +'img[data-testid="cover-art-image"]:not(#spl-cover-img){width:64px!important;height:64px!important;}';
                            document.head.appendChild(st);
                        }
                    }else if(st){ st.remove(); }
                },300);
            }

            // ---------- CROSSFADE (el.volume based) ----------
            // MSE elements report duration=Infinity; fall back to the progressbar max.
            function trackDuration(el){
                try{
                    var d=el.duration;
                    if(isFinite(d)&&d>0) return d;
                }catch(e){}
                try{
                    var rg=document.querySelector('[data-testid="playback-progressbar"] input[type=range]');
                    if(rg){ var mx=parseFloat(rg.getAttribute('max')); if(isFinite(mx)&&mx>0) return mx; }
                }catch(e){}
                return 0;
            }
            var wiredEl=null;
            function onTimeUpdate(){
                try{
                    var el=wiredEl; if(!el) return;
                    if(S.crossfade<=0){ if(el.volume!==1) el.volume=1; return; }
                    var sec=Math.min(S.crossfade,12);
                    var dur=trackDuration(el);
                    if(!dur||el.paused){ el.volume=1; return; }
                    var v=1;
                    var remain=dur-el.currentTime;
                    // fade-out over the last `sec` seconds
                    if(remain<=sec&&remain>0){
                        v=Math.min(v,Math.max(0.05,remain/sec));
                    }
                    // fade-in after a fresh track (loadedmetadata stamps the deadline)
                    var inUntil=window.__splXfInUntil||0;
                    if(inUntil>0){
                        var left=(inUntil-Date.now())/1000;
                        if(left>0) v=Math.min(v,Math.max(0.05,1-left/Math.min(sec,4)));
                        else window.__splXfInUntil=0;
                    }
                    el.volume=v;
                }catch(e){}
            }
            function onLoadedMeta(){
                try{
                    var el=wiredEl; if(!el) return;
                    if(S.crossfade>0){
                        window.__splXfInUntil=Date.now()+Math.min(S.crossfade,4)*1000;
                        dbg('xfade in-armed: '+el.src.slice(0,60));
                    }
                    onTimeUpdate();
                }catch(e){}
            }
            var lastWire=0;
            function wireMedia(){
                var now=Date.now();
                if(now-lastWire<2000&&wiredEl) return;  // MutationObserver churn guard
                lastWire=now;
                var el=document.querySelector('audio[src]')||document.querySelector('audio')||document.querySelector('video');
                if(!el) return;
                if(wiredEl===el){ onTimeUpdate(); return; }
                wiredEl=el;
                el.addEventListener('timeupdate',onTimeUpdate);
                el.addEventListener('loadedmetadata',onLoadedMeta);
                el.addEventListener('play',onTimeUpdate);
                dbg('xfade wired: '+S.crossfade+'s cap='+capBits());
                onTimeUpdate();
            }
            try{
                var mo=new MutationObserver(function(){ wireMedia(); });
                mo.observe(document.documentElement,{childList:true,subtree:true});
                wireMedia();
            }catch(e){ wireMedia(); }
            setTimeout(wireMedia,5000);

            // ---------- PUBLIC API ----------
            window.splAudio={
                get:function(){ return JSON.parse(JSON.stringify(S)); },
                set:function(patch){
                    for(var k in patch){ if(k in S) S[k]=patch[k]; }
                    save(); applyDataSaver(); notify();
                    // re-apply immediately if crossfade just changed
                    try{
                        if(wiredEl){ onLoadedMeta(); }
                    }catch(e){}
                    dbg('prefs: '+JSON.stringify(S));
                    return window.splAudio.get();
                },
                setCrossfade:function(sec){ return window.splAudio.set({crossfade:Math.max(0,Math.min(12,Number(sec)||0))}); },
                setQuality:function(q){ return window.splAudio.set({quality:Math.max(-1,Math.min(2,Number(q)||0))}); },
                setDataSaver:function(b){ return window.splAudio.set({dataSaver:!!b}); },
                dump:function(){ return JSON.stringify(S); }
            };

            window.splAudioRestore=function(json){
                try{
                    var p=JSON.parse(json);
                    for(var k in p){ if(k in S) S[k]=p[k]; }
                    save(); applyDataSaver();
                    if(S.crossfade<=0&&wiredEl&&wiredEl.volume!==1) wiredEl.volume=1;
                }catch(e){}
            };

            applyDataSaver();
        })();
    """
}