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

import javax.xml.namespace.QName;

import org.apache.cxf.binding.soap.SoapFault;
import org.opennms.core.wsman.WSManConstants;
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

    /**
     * Ceiling for the per-Receive {@code OperationTimeout}. Windows clamps very large
     * values silently and the caller's overall deadline check only fires between
     * Receives — keeping a sensible ceiling means a long-budget caller (minutes or
     * hours) still gets timely deadline enforcement instead of being trapped inside
     * one giant blocking Receive. There is deliberately no floor: a caller passing
     * a sub-second timeout gets a sub-second OperationTimeout, honoring their
     * deadline rather than the server's preferred parking duration.
     */
    private static final Duration RECEIVE_OP_TIMEOUT_MAX = Duration.ofSeconds(60);

    /** The {@code wsman:TimedOut} subcode the server returns when a Receive's
     *  OperationTimeout elapses with no output; benign and expected — caller retries. */
    private static final QName WSMAN_TIMED_OUT =
        new QName(WSManConstants.XML_NS_DMTF_WSMAN_V1, "TimedOut");

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
     * <p>Stdout and stderr are decoded as UTF-8 and returned as {@link String} values in
     * the {@link CommandResult}. If the shell is configured with a non-UTF-8 code page,
     * the returned text may be decoded incorrectly; callers cannot re-decode the original
     * bytes from {@link CommandResult}. UTF-8 (code page 65001) is the recommended default.
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
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutReached();
            }
            // Server-side OperationTimeout for this Receive: track the caller's
            // remaining budget so we don't park past the overall deadline, capped at
            // RECEIVE_OP_TIMEOUT_MAX so long-budget callers retain prompt deadline
            // enforcement.
            Duration remaining = Duration.ofNanos(remainingNanos);
            Duration opTimeout = remaining.compareTo(RECEIVE_OP_TIMEOUT_MAX) > 0
                ? RECEIVE_OP_TIMEOUT_MAX : remaining;

            Element receiveResponse;
            try {
                receiveResponse = ops.receive(shellId,
                    ShellBodyBuilder.buildReceiveBody(commandId), opTimeout);
            } catch (RuntimeException e) {
                if (isWsmanTimedOut(e)) {
                    // Benign: server held the Receive for OperationTimeout with no
                    // output. Loop, re-check the caller deadline, and re-issue.
                    LOG.trace("WinRS Receive: server returned TimedOut fault, retrying");
                    continue;
                }
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

    /**
     * Walks the cause chain looking for a {@link SoapFault} whose subcode is
     * {@code wsman:TimedOut} — the server's "no output yet, please retry" signal.
     */
    private static boolean isWsmanTimedOut(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SoapFault) {
                QName sub = ((SoapFault) cur).getSubCode();
                if (sub != null && WSMAN_TIMED_OUT.equals(sub)) return true;
            }
        }
        return false;
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