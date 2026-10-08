package com.bruh.angel.shizuku;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IShellService {
    void destroy() = 16777114;
    int uid() = 0;
    /** [distro] is the LinuxDistro id; ignored unless [linux] is true. */
    Bundle execute(String command, boolean linux, String distro) = 1;
    void cancel() = 2;
    String linuxStatus(String distro) = 3;
    void installArtifact(String distro, String name, in ParcelFileDescriptor archive) = 4;
    String finishInstall(String distro) = 5;

    /** Starts a persistent interactive shell (no TTY). Reads stdin from input, writes stdout+stderr to output. */
    int openTerminal(boolean linux, String distro, in ParcelFileDescriptor input, in ParcelFileDescriptor output) = 6;
    /** Only SIGINT (2) is accepted: interrupts the running foreground command, keeps the shell. */
    void signalTerminal(int id, int signal) = 7;
    void closeTerminal(int id) = 8;

    /** Compact list of on-screen UI elements (uiautomator dump), optionally filtered by text. */
    String readScreen(String filter) = 9;

    /** Comma-separated ids of the installed (ready) Linux distributions. */
    String linuxInstalled() = 10;
    /** Deletes an installed distribution, closing its terminals first. */
    void removeLinux(String distro) = 11;

    /**
     * Starts a long-running, non-interactive process (an MCP server or an installer) in the Linux userland
     * or the Android shell. [env] holds KEY=VALUE pairs. stdin is read from [stdin]; stdout and stderr are
     * written to [stdout] and [stderr] (pass the same pipe twice to merge them). Returns a spawn id.
     */
    int spawn(boolean linux, String distro, String command, in String[] env,
              in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr) = 12;
    /** Exit code of a finished spawn (and forgets it), -1 while it is still running, -2 if unknown. */
    int spawnExit(int id) = 13;
    /** SIGKILLs the whole process group of a spawn. */
    void killSpawn(int id) = 14;
}


