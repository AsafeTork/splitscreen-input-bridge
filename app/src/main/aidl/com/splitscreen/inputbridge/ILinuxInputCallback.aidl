package com.splitscreen.inputbridge;

interface ILinuxInputCallback {
    void onGamepadEvent(int player, int type, int code, int value);
}
