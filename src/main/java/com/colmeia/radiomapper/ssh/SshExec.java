package com.colmeia.radiomapper.ssh;

import java.io.IOException;

public final class SshExec {

    private SshExec() {}

    public static String run(String host, int port, String user, String password, String command) throws IOException {
        return SshSessionPool.INSTANCE.exec(host, port, user, password, command);
    }
}
