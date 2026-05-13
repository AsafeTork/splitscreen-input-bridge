package com.splitscreen.inputbridge;

import android.view.InputEvent;

interface IShizukuUserService {
    void onCreate();
    boolean injectInputEvent(in InputEvent event, int mode);
    String execShell(String command);
    void destroy();
}
