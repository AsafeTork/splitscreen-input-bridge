package com.splitscreen.inputbridge;

interface ILinuxInputCallback {
    void onGamepadEvent(int deviceId, int type, int code, int value);
}
