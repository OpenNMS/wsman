/*
 * Copyright (C) The OpenNMS Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opennms.core.wsman.shell;

import java.util.Objects;

/**
 * Result of executing a single command via WinRS.
 *
 * The exit code is the process exit status reported by the Windows shell.
 * Stdout and stderr are the UTF-8 (or configured code page) decoded contents
 * of the corresponding output streams accumulated across all WinRS Receive
 * responses for the command.
 */
public final class CommandResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public CommandResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommandResult)) return false;
        CommandResult that = (CommandResult) o;
        return exitCode == that.exitCode
            && stdout.equals(that.stdout)
            && stderr.equals(that.stderr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exitCode, stdout, stderr);
    }

    @Override
    public String toString() {
        return "CommandResult[exitCode=" + exitCode
            + ", stdout=" + stdout.length() + "B, stderr=" + stderr.length() + "B]";
    }
}
