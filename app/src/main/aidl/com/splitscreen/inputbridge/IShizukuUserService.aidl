package com.splitscreen.inputbridge;

import android.view.InputEvent;
import com.splitscreen.inputbridge.ILinuxInputCallback;

interface IShizukuUserService {
    void onCreate();
    boolean injectInputEvent(in InputEvent event, int mode);
    String execShell(String command);
    void startLinuxInputReader(ILinuxInputCallback callback);
    void stopLinuxInputReader();
    void destroy();
}
