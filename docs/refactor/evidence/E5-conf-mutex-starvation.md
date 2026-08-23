# E5 — proof of the conference-mutex starvation

Captured 2026-08-23 22:04:44 on merlinx (Redmi Note 9, MT6768), debug build,
APK versionCode 4 from refactor/phase-0. `debuggerd -b` fired automatically on the
`pjsua_media.c ... deinitializing media` log line, ~0.5 s into the block.

Two threads, same instant. Symbols trimmed for readability; full dumps were
1042/977/993 lines.

## Waiter — pjsua worker, still inside handling the received BYE
```
"Thread-46427" sysTid=4525
    #00 pc 00000000000cd9dc  libc.so (syscall+28) 
    #01 pc 00000000000a3ed0  libc.so (__futex_wait_ex(void volatile*, bool, int, bool, timespec const*)+144) 
    #02 pc 00000000000b6488  libc.so (NonPI::MutexLockWithTimeout(pthread_mutex_internal_t*, bool, timespec const*) (.__uniq.8908157144215114713334022205380908012)+552) 
    #03 pc 00000000003d016c  libpjsua2.so (offset 0x4f2000) (pj_mutex_lock+28) 
    #04 pc 00000000002f35b0  libpjsua2.so (offset 0x4f2000) (pjmedia_conf_remove_port+44) 
    #05 pc 000000000028f684  libpjsua2.so (offset 0x4f2000) (pjsua_aud_stop_stream+148) 
    #06 pc 0000000000287538  libpjsua2.so (offset 0x4f2000) 
    #07 pc 0000000000285b48  libpjsua2.so (offset 0x4f2000) (pjsua_media_channel_deinit+536) 
    #08 pc 000000000026f790  libpjsua2.so (offset 0x4f2000) 
    #09 pc 0000000000299f00  libpjsua2.so (offset 0x4f2000) 
    #10 pc 000000000029ff8c  libpjsua2.so (offset 0x4f2000) 
    #11 pc 000000000029c8f0  libpjsua2.so (offset 0x4f2000) 
    #12 pc 00000000002d4d84  libpjsua2.so (offset 0x4f2000) (pjsip_dlg_on_tsx_state+168) 
    #13 pc 00000000002ce9f8  libpjsua2.so (offset 0x4f2000) 
    #14 pc 00000000002ce0c0  libpjsua2.so (offset 0x4f2000) 
    #15 pc 00000000002cee98  libpjsua2.so (offset 0x4f2000) (pjsip_tsx_recv_msg+140) 
    #16 pc 00000000002d43a8  libpjsua2.so (offset 0x4f2000) (pjsip_dlg_on_rx_request+572) 
    #17 pc 00000000002d5eb8  libpjsua2.so (offset 0x4f2000) 
    #18 pc 00000000002bc4b4  libpjsua2.so (offset 0x4f2000) (pjsip_endpt_process_rx_data+424) 
    #19 pc 00000000002bbd00  libpjsua2.so (offset 0x4f2000) 
    #20 pc 00000000002c276c  libpjsua2.so (offset 0x4f2000) (pjsip_tpmgr_receive_packet+164) 
```

## Holder — conference clock thread, blocked in the ALSA ioctl
```
"Thread-46451" sysTid=13991
    #00 pc 000000000010e688  libc.so (__ioctl+8) 
    #01 pc 00000000000af39c  libc.so (ioctl+156) 
    #02 pc 00000000000073e0  libgsm_audio.so (offset 0x2e6000) (pcm_read+232) 
    #03 pc 00000000000050a4  libgsm_audio.so (offset 0x2e6000) (Java_org_onetwoone_gateway_GsmAudioNative_readFrame+208) 
    #04 pc 00000000002e8b00  /apex/com.android.art/lib64/libart.so (art_quick_generic_jni_trampoline+144) 
    #05 pc 0000000004625ef4  /memfd:jit-cache (deleted) (offset 0x4000000) (org.onetwoone.gateway.GsmAudioPort.onFrameRequested+388)
    #06 pc 0000000004626ec0  /memfd:jit-cache (deleted) (offset 0x4000000) (org.pjsip.pjsua2.pjsua2JNI.SwigDirector_AudioMediaPort_onFrameRequested+176)
    #07 pc 00000000002d1a60  /apex/com.android.art/lib64/libart.so (art_quick_invoke_static_stub+640) 
    #08 pc 00000000002d0744  /apex/com.android.art/lib64/libart.so (art::JValue art::InvokeWithVarArgs<_jmethodID*>(art::ScopedObjectAccessAlreadyRunnable const&, _jobject*, _jmethodID*, std::__va_list)+884) 
    #09 pc 00000000006193b8  /apex/com.android.art/lib64/libart.so (art::JNI<true>::CallStaticVoidMethodV(_JNIEnv*, _jclass*, _jmethodID*, std::__va_list)+168) 
    #10 pc 000000000034451c  /apex/com.android.art/lib64/libart.so (art::(anonymous namespace)::CheckJNI::CallMethodV(char const*, _JNIEnv*, _jobject*, _jclass*, _jmethodID*, std::__va_list, art::Primitive::Type, art::InvokeType) (.__uniq.99033978352804627313491551960229047428)+348) 
    #11 pc 000000000082e7cc  /apex/com.android.art/lib64/libart.so (art::(anonymous namespace)::CheckJNI::CallStaticVoidMethodV(_JNIEnv*, _jclass*, _jmethodID*, std::__va_list) (.__uniq.99033978352804627313491551960229047428.llvm.7517547034924663949)+60) 
    #12 pc 0000000000187698  libpjsua2.so (offset 0x4f2000) (_JNIEnv::CallStaticVoidMethod(_jclass*, _jmethodID*, ...)+96) 
    #13 pc 000000000018755c  libpjsua2.so (offset 0x4f2000) (SwigDirector_AudioMediaPort::onFrameRequested(pj::MediaFrame&)+352) 
    #14 pc 0000000000256fe0  libpjsua2.so (offset 0x4f2000) 
    #15 pc 00000000002f1ab0  libpjsua2.so (offset 0x4f2000) 
    #16 pc 00000000002f9068  libpjsua2.so (offset 0x4f2000) 
    #17 pc 00000000002efbd8  libpjsua2.so (offset 0x4f2000) 
    #18 pc 00000000003d02a0  libpjsua2.so (offset 0x4f2000) 
    #19 pc 00000000000b498c  libc.so (__pthread_start(void*) (.__uniq.67847048707805468364044055584648682506)+236) 
    #20 pc 00000000000a5320  libc.so (__start_thread+64) 
```

## Reading

Frames #14-#17 of the holder are unnamed `libpjsua2.so` addresses: the conference
bridge internals (`conf.c` `get_frame`) that own the mutex across the whole callback.
The waiter is at `pjmedia_conf_remove_port+44`, i.e. its very first act is to take
that mutex. Because the callback re-enters every 20 ms tick, the mutex is held
almost continuously and a plain non-FIFO `pthread_mutex` acquire starves for an
unbounded time — matching the measured spread of 1.8-50.7 s.

**Caveat:** this is a debuggable build, so `CheckJNI` is active (visible at frames
#10-#11 of the holder) and inflates every JNI crossing. That worsens the hold time
but is not the cause — the block is the `ioctl` in `pcm_read`, which a release build
does identically. Re-measure the spread on a release build before quoting numbers.
