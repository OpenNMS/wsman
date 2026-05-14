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
package org.opennms.core.wsman.cxf.shell;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.opennms.core.wsman.exceptions.WSManException;
import org.opennms.core.wsman.shell.CommandResult;
import org.opennms.core.wsman.shell.ShellOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * Orchestrates a single-command WinRS lifecycle: Create shell → Command → Receive-loop →
 * Delete. Designed for short-lived monitoring use cases where the shell exists only long
 * enough to run one command and capture its output.
 *
 * <p>The shell is created lazily on first {@link #runCommand} and torn down by
 * {@link #close()}; the typical idiom is try-with-resources:
 * <pre>
 *   try (WinRSClient winrs = new WinRSClient(ops, options)) {
 *       CommandResult r = winrs.runCommand("ipconfig", new String[]{"/all"}, Duration.ofMinutes(2));
 *   }
 * </pre>
 *
 * <p>If a command exceeds its timeout, a Terminate signal is sent best-effort before the
 * shell is deleted and a {@link WSManException} is raised.
 */
public class WinRSClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WinRSClient.class);

    private final ShellOperations ops;
    private final ShellOptions options;
    private String shellId;

    public WinRSClient(ShellOperations ops, ShellOptions options) {
        this.ops = Objects.requireNonNull(ops, "ops");
        this.options = options != null ? options : ShellOptions.defaults();
    }

    /**
     * Runs a single command in this client's shell (creating the shell on first call) and
     * blocks until the command completes, the timeout elapses, or an error occurs.
     *
     * <p>Stdout and stderr are decoded as UTF-8. If the shell is configured with a
     * non-UTF-8 code page, callers can re-decode {@link CommandResult#stdout()} bytes
     * themselves — but UTF-8 (codepage 65001) is the recommended default.
     */
    public CommandResult runCommand(String executable, String[] args, Duration timeout) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(timeout, "timeout");

        ensureShell();

        Element commandResponse;
        try {
            commandResponse = ops.command(shellId, ShellBodyBuilder.buildCommandBody(executable, args));
        } catch (RuntimeException e) {
            throw new WSManException("WinRS Command failed for executable=" + executable, e);
        }
        String commandId = ShellResponseParser.extractCommandId(commandResponse)
            .orElseThrow(() -> new WSManException("WinRS Command response did not contain a CommandId"));

        try {
            return receiveOutput(commandId, timeout);
        } catch (TimeoutReached t) {
            sendTerminateBestEffort(commandId);
            throw new WSManException("WinRS command '" + executable + "' did not complete within " + timeout);
        }
    }

    private void ensureShell() {
        if (shellId != null) return;
        Element response;
        try {
            response = ops.create(ShellBodyBuilder.buildCreateBody(options), options);
        } catch (RuntimeException e) {
            throw new WSManException("WinRS Create-shell failed", e);
        }
        shellId = ShellResponseParser.extractShellId(response)
            .orElseThrow(() -> new WSManException("WinRS Create-shell response did not contain a ShellId"));
        LOG.debug("WinRS shell opened: shellId={}", shellId);
    }

    private CommandResult receiveOutput(String commandId, Duration timeout) throws TimeoutReached {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Integer exitCode = null;

        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                throw new TimeoutReached();
            }
            Element receiveResponse;
            try {
                receiveResponse = ops.receive(shellId,
                    ShellBodyBuilder.buildReceiveBody(commandId));
            } catch (RuntimeException e) {
                throw new WSManException("WinRS Receive failed", e);
            }
            ShellResponseParser.ReceiveChunk chunk =
                ShellResponseParser.parseReceiveResponse(receiveResponse);

            byte[] outBytes = chunk.stdoutBytes();
            if (outBytes.length > 0) stdout.write(outBytes, 0, outBytes.length);
            byte[] errBytes = chunk.stderrBytes();
            if (errBytes.length > 0) stderr.write(errBytes, 0, errBytes.length);

            if (chunk.isDone()) {
                // -1 sentinel when the server reports Done but no ExitCode element (rare,
                // but our parser tolerates it). The caller can treat -1 as "unknown".
                exitCode = chunk.getExitCode() != null ? chunk.getExitCode() : -1;
                break;
            }
        }

        return new CommandResult(
            exitCode,
            stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8));
    }

    private void sendTerminateBestEffort(String commandId) {
        try {
            ops.signal(shellId, ShellBodyBuilder.buildSignalBody(commandId, ShellConstants.SIGNAL_TERMINATE));
        } catch (Exception e) {
            LOG.debug("WinRS Terminate signal failed (best-effort, ignoring): {}", e.toString());
        }
    }

    /**
     * Deletes the shell if it was created. Idempotent; safe to call multiple times.
     * Failures during Delete are logged at debug level and not propagated — the caller
     * cannot meaningfully recover from a cleanup error.
     */
    @Override
    public void close() {
        if (shellId == null) return;
        String id = shellId;
        shellId = null;
        try {
            ops.delete(id);
            LOG.debug("WinRS shell deleted: shellId={}", id);
        } catch (Exception e) {
            LOG.debug("WinRS Delete-shell failed (ignoring): shellId={}, error={}", id, e.toString());
        }
    }

    /** Internal sentinel — propagates a deadline-exceeded signal out of the receive loop. */
    private static final class TimeoutReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
        TimeoutReached() { super(null, null, false, false); }
    }
}