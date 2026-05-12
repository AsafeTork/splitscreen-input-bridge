// IShizukuUserService.aidl
// AIDL interface for the Shizuku privileged user service.
// This file is processed by the AIDL compiler to generate Java/Kotlin bindings.
//
// Place this file at:
//   app/src/main/aidl/com/splitscreen/inputbridge/IShizukuUserService.aidl
//
// The generated Stub class (IShizukuUserService.Stub) is what
// ShizukuPrivilegedUserService extends.

package com.splitscreen.inputbridge;

import android.view.InputEvent;

interface IShizukuUserService {
    /**
     * Called by Shizuku to initialize the service.
     * Runs inside the Shizuku privileged process (shell/root UID).
     */
    void onCreate() = 1;

    /**
     * Called before the service is destroyed.
     */
    void destroy() = 2;

    /**
     * Inject an InputEvent via IInputManager.injectInputEvent().
     *
     * @param event The event to inject (MotionEvent or KeyEvent).
     * @param mode  Injection mode:
     *                0 = INJECT_INPUT_EVENT_MODE_ASYNC
     *                1 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
     *                2 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
     * @return true if the event was successfully dispatched.
     */
    boolean injectInputEvent(in InputEvent event, int mode) = 3;

    /**
     * Execute a shell command in the Shizuku privileged process.
     *
     * @param command Shell command string.
     * @return stdout output of the command.
     */
    String execShell(String command) = 4;
}
