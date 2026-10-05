package com.project.lol.webview.injections

object AudioSettings {
    // Quality enum: -1 auto, 0 low(96), 1 normal(160), 2 high(320), 3 very_high, 4 lossless
    const val CONTENT = """
        (function(){
            if(window.__splAudioSettings) return; window.__splAudioSettings=true;

            var LS='spotilol.audio.';
            var S={
                crossfade: 0,          // seconds 0..12
                gapless: true,
                automix: false,
                quality: -1,           // -1 auto,0 low,1 normal,2 high,3 vhigh,4 lossless
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
                if(raw){ var p=JSON.parse(raw); for(var k in p){ if(k in S) S[k]=p[k]; } }
            }catch(e){}
            function save(){ try{ localStorage.setItem(LS+'v1',JSON.stringify(S)); }catch(e){} }
            function notify(){
                try{ AndBridge.audioPrefsChanged(JSON.stringify(S)); }catch(e){}
            }

            // ---------- SERVER WRITE: esperanto ProductState over dealer WS ----------
            // Service: spotify.product_state.esperanto.proto.ProductState  method: PutValues
            // transport: wss://<dealer from apresolve> ; payload: protobuf (not JSON)
            // TODO(live): wire full protobuf + dealer handshake once captured from desktop devtools.
            // Keys written server-side: quality.streamingQuality, audio.crossfade_v2,
            //   audio.crossfade.time_v2, audio.gapless_v2, audio.automix
            window.splAudioServerSync = function(){
                if(!S.dataSaver && S.quality<0 && S.crossfade===0 && S.gapless) return;
                try{
                    AndBridge.dbg('a','serverSync skip (esperanto WS pending): '+JSON.stringify(S));
                }catch(e){}
            };

            // ---------- CLIENT-SIDE EFFECTS ----------
            // Crossfade: Web Audio gain ramp on Spotify's <audio> element.
            // True overlap needs next-track source (Spotify MSE, not exposed) -> fade-out+fade-in.
            var ac=null, gainNode=null, hookedEl=null;
            function ensureCtx(){
                if(ac) return ac;
                try{
                    ac=new (window.AudioContext||window.webkitAudioContext)();
                    gainNode=ac.createGain(); gainNode.gain.value=1;
                    gainNode.connect(ac.destination);
                }catch(e){ ac=null; }
                return ac;
            }
            function hookEl(el){
                if(!el||hookedEl===el||!ensureCtx()) return;
                try{
                    var src=ac.createMediaElementSource(el);
                    src.connect(gainNode);
                    hookedEl=el;
                }catch(e){}
            }
            var lastEnd=0;
            function tick(){
                if(S.crossfade<=0||!gainNode||!hookedEl) return;
                try{
                    var el=hookedEl;
                    if(el.paused||!isFinite(el.duration)) return;
                    var remain=(el.duration-el.currentTime)*1000;
                    var fade=Math.min(S.crossfade*1000, el.duration*500);
                    var now=ac.currentTime;
                    gainNode.gain.cancelScheduledValues(now);
                    if(remain<=fade && remain>0){
                        gainNode.gain.setValueAtTime(remain/fade, now);
                    }else{
                        gainNode.gain.setValueAtTime(1, now);
                    }
                }catch(e){}
            }
            // hook player element + track change (fade-in)
            var tries=0;
            var iv=setInterval(function(){
                tries++;
                var el=document.querySelector('audio[src], audio') ||
                        document.querySelector('.Root video')?.parentElement?.querySelector('audio');
                if(el){ hookEl(el); }
                // fade-in on new track
                try{
                    var tid=(window.__curTrackId||'');
                    if(tid && tid!==lastEnd){
                        lastEnd=tid;
                        if(gainNode&&S.crossfade>0){
                            var now=ac.currentTime;
                            var fade=Math.min(S.crossfade,3);
                            gainNode.gain.cancelScheduledValues(now);
                            gainNode.gain.setValueAtTime(0,now);
                            gainNode.gain.linearRampToValueAtTime(1,now+fade);
                        }
                    }
                }catch(e){}
                if(tries>120) clearInterval(iv);
            },500);
            setInterval(tick,250);

            // Data Saver: block heavy images + video via CSS + fetch hook
            function applyDataSaver(){
                var st=document.getElementById('spl-datasaver-css');
                if(S.dataSaver){
                    if(!st){
                        st=document.createElement('style'); st.id='spl-datasaver-css';
                        st.textContent='img:not(#spl-cover-img):not([data-testid="cover-art-image"]){filter:brightness(.7) saturate(.7)!important;}'
                            +'.CoverArtImage, [data-testid="cover-art"] img{width:48px!important;height:48px!important;}'
                            +'video{display:none!important;}';
                        document.head.appendChild(st);
                    }
                }else if(st){ st.remove(); }
            }
            applyDataSaver();

            // ---------- PUBLIC API ----------
            window.splAudio={
                get: function(){ return JSON.parse(JSON.stringify(S)); },
                set: function(patch){
                    for(var k in patch){ if(k in S) S[k]=patch[k]; }
                    save(); applyDataSaver(); notify();
                    return window.splAudio.get();
                },
                // helpers
                setCrossfade: function(sec){ return window.splAudio.set({crossfade:Math.max(0,Math.min(12,Number(sec)||0))}); },
                setGapless: function(b){ return window.splAudio.set({gapless:!!b}); },
                setAutomix: function(b){ return window.splAudio.set({automix:!!b}); },
                setQuality: function(q){ return window.splAudio.set({quality:Math.max(-1,Math.min(4,Number(q)||0))}); },
                setDataSaver: function(b){ return window.splAudio.set({dataSaver:!!b}); },
                dump: function(){ return JSON.stringify(S); }
            };

            // restore from native
            window.splAudioRestore=function(json){
                try{
                    var p=JSON.parse(json);
                    for(var k in p){ if(k in S) S[k]=p[k]; }
                    save(); applyDataSaver();
                }catch(e){}
            };
        })();
    """
}