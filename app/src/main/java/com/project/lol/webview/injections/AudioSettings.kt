package com.project.lol.webview.injections

/*
 * Audio settings: crossfade / gapless / automix / streaming quality / data saver.
 *
 * SCOPED CAREFULLY — Spotify's web player exposes these prefs as abstract
 * (class `ab` in web-player.a6d2a638.js throws "not supported"): the real
 * writes go through the esperanto ProductState service over wss://dealer
 * (protobuf), not reachable from the WebView. What we CAN do locally:
 *   - persist the user's choice (localStorage + SharedPreferences via bridge)
 *   - apply crossfade client-side ONLY when explicitly enabled (opt-in)
 *   - data saver: CSS/asset-side effects
 * The Web Audio gain node is created ONLY if crossfade > 0, and audio is never
 * routed through a suspended AudioContext (that caused silent playback in an
 * earlier build). No periodic timers: gain rides the media element's own
 * `timeupdate` event instead of a 250ms setInterval.
 */
object AudioSettings {
    const val CONTENT = """
        (function(){
            if(window.__splAudioSettings) return; window.__splAudioSettings=true;

            var LS='spotilol.audio.';
            var S={
                crossfade: 0,          // seconds 0..12 (0 = off)
                gapless: true,
                automix: false,
                quality: -1,           // -1 auto,0 low,1 normal,2 high,3 vhigh
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

            // ---------- DATA SAVER (safe, CSS only) ----------
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

            // ---------- CROSSFADE (opt-in; audio untouched when off) ----------
            var ac=null, gainNode=null, hookedEl=null;
            function ensureCtx(){
                if(ac) return ac;
                if(S.crossfade<=0) return null;   // opt-in gate: never build a ctx for nothing
                try{
                    ac=new (window.AudioContext||window.webkitAudioContext)();
                    gainNode=ac.createGain(); gainNode.gain.value=1;
                    gainNode.connect(ac.destination);
                }catch(e){ ac=null; gainNode=null; }
                return ac;
            }
            function hookEl(el){
                if(!el||hookedEl===el||!ensureCtx()) return;
                try{
                    var src=ac.createMediaElementSource(el);
                    src.connect(gainNode);
                    hookedEl=el;
                }catch(e){ /* already routed / unsupported: leave audio alone */ }
            }
            function onTimeUpdate(){
                if(S.crossfade<=0||!gainNode||!hookedEl||hookedEl.paused) return;
                try{
                    var el=hookedEl;
                    if(!isFinite(el.duration)||el.duration<=0) return;
                    var remain=(el.duration-el.currentTime)*1000;
                    var fade=Math.min(S.crossfade*1000, el.duration*500);
                    var now=ac.currentTime;
                    gainNode.gain.cancelScheduledValues(now);
                    if(remain<=fade&&remain>0){
                        gainNode.gain.setValueAtTime(Math.max(0.001,remain/fade),now);
                    }else{
                        gainNode.gain.setValueAtTime(1,now);
                    }
                }catch(e){}
            }
            function wireMedia(){
                var el=document.querySelector('audio[src]')||document.querySelector('audio')||document.querySelector('video');
                if(!el||el.__splXfWired) return;
                el.__splXfWired=true;
                if(S.crossfade>0) hookEl(el);
                el.addEventListener('timeupdate',onTimeUpdate);
                el.addEventListener('loadedmetadata',function(){
                    if(S.crossfade<=0||!gainNode) return;
                    try{
                        var now=ac.currentTime, fade=Math.min(S.crossfade,3);
                        gainNode.gain.cancelScheduledValues(now);
                        gainNode.gain.setValueAtTime(0.001,now);
                        gainNode.gain.linearRampToValueAtTime(1,now+fade);
                    }catch(e){}
                });
            }
            try{
                var mo=new MutationObserver(function(){ wireMedia(); });
                mo.observe(document.documentElement,{childList:true,subtree:true});
                wireMedia();
            }catch(e){ wireMedia(); }
            // player mounts late -> one extra attempt (no polling loop)
            setTimeout(wireMedia,5000);

            // ---------- PUBLIC API ----------
            window.splAudio={
                get:function(){ return JSON.parse(JSON.stringify(S)); },
                set:function(patch){
                    var wasXf=S.crossfade;
                    for(var k in patch){ if(k in S) S[k]=patch[k]; }
                    save(); applyDataSaver(); notify();
                    try{
                        if(S.crossfade>0&&wasXf<=0){ hookedEl=null; wireMedia(); }
                        else if(S.crossfade<=0&&gainNode&&gainNode.gain){ gainNode.gain.value=1; }
                    }catch(e){}
                    return window.splAudio.get();
                },
                setCrossfade:function(sec){ return window.splAudio.set({crossfade:Math.max(0,Math.min(12,Number(sec)||0))}); },
                setGapless:function(b){ return window.splAudio.set({gapless:!!b}); },
                setAutomix:function(b){ return window.splAudio.set({automix:!!b}); },
                setQuality:function(q){ return window.splAudio.set({quality:Math.max(-1,Math.min(3,Number(q)||0))}); },
                setDataSaver:function(b){ return window.splAudio.set({dataSaver:!!b}); },
                dump:function(){ return JSON.stringify(S); }
            };

            window.splAudioRestore=function(json){
                try{
                    var p=JSON.parse(json);
                    for(var k in p){ if(k in S) S[k]=p[k]; }
                    save(); applyDataSaver();
                    if(S.crossfade<=0&&gainNode&&gainNode.gain) gainNode.gain.value=1;
                }catch(e){}
            };

            applyDataSaver();
        })();
    """
}