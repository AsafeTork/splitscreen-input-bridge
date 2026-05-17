package com.splitscreen.inputbridge;

import android.view.InputEvent;
import com.splitscreen.inputbridge.ILinuxInputCallback;

interface IShizukuUserService {
    void onCreate();
    boolean injectInputEvent(in InputEvent event, int mode);
    String execShell(String command);
    void startLinuxInputReader(ILinuxInputCallback callback,
                              int p1Vendor, int p1Product, String p1Name,
                              int p2Vendor, int p2Product, String p2Name);
    void stopLinuxInputReader();
    void destroy();
}
